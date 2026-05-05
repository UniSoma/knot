(ns knot.check
  "Project-integrity validation. Walks tickets + config and emits a
   sorted vector of issue records, each
   `{:severity :error|:warning :code <kw> :ids [<id>...] :message <s>
     :path? <s> :field? <kw> :value? <any>}`. Pure: callers supply
   already-loaded tickets and scan counts."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [knot.config :as config]
            [knot.query :as query]
            [knot.ticket :as ticket]))

(defn- dep-cycle-issue
  "Build a :dep_cycle error issue from a cycle path `[v ... v]`."
  [cycle-path]
  {:severity :error
   :code     :dep_cycle
   :ids      (vec cycle-path)
   :message  (str "dep cycle: " (str/join " -> " cycle-path))})

(defn- cycle-issues
  "Run dep-cycle detection across `tickets` and emit one issue per cycle."
  [tickets]
  (mapv dep-cycle-issue (query/project-cycles tickets)))

(defn- enum-issue
  "Build an `invalid_<field>` error issue when `value` is not in `allowed`."
  [code field allowed id value]
  {:severity :error
   :code     code
   :ids      [id]
   :field    field
   :value    value
   :message  (str "invalid " (name field) " " (pr-str value)
                  ": expected one of " (pr-str (vec allowed)))})

(defn- check-enum
  "Generic per-ticket enum validator: when ticket has `field` set, it must
   appear in `(get config config-key)`."
  [code field config-key]
  (fn [{:keys [config]} ticket]
    (let [{:keys [id]} (:frontmatter ticket)
          value        (get (:frontmatter ticket) field)
          allowed      (get config config-key)]
      (when (and (some? value)
                 (seq allowed)
                 (not (contains? (set allowed) value)))
        [(enum-issue code field allowed id value)]))))

(def ^:private check-status (check-enum :invalid_status :status :statuses))
(def ^:private check-type   (check-enum :invalid_type   :type   :types))
(def ^:private check-mode   (check-enum :invalid_mode   :mode   :modes))

(defn- check-priority
  "Per-ticket: priority, when present, must be an integer in 0..4."
  [_ctx ticket]
  (let [{:keys [id priority]} (:frontmatter ticket)]
    (when (and (some? priority)
               (not (and (integer? priority) (<= 0 priority 4))))
      [{:severity :error
        :code     :invalid_priority
        :ids      [id]
        :field    :priority
        :value    priority
        :message  (str "invalid priority " (pr-str priority)
                       ": expected integer in 0..4")}])))

(defn- check-terminal-outside-archive
  "Per-ticket: terminal-status tickets must live under archive/, and
   non-terminal tickets must live outside archive/. Both directions emit
   the same code; the message distinguishes."
  [{:keys [config]} ticket]
  (let [{:keys [id status]} (:frontmatter ticket)
        terminal-statuses   (:terminal-statuses config)
        archived?           (:archived? ticket)
        is-terminal?        (and status
                                 (contains? (or terminal-statuses #{}) status))]
    (cond
      (and is-terminal? (not archived?))
      [{:severity :error
        :code     :terminal_outside_archive
        :ids      [id]
        :path     (:path ticket)
        :message  (str "terminal-status ticket " (pr-str id)
                       " (status " (pr-str status)
                       ") is outside archive/")}]

      (and (not is-terminal?) archived? status)
      [{:severity :error
        :code     :terminal_outside_archive
        :ids      [id]
        :path     (:path ticket)
        :message  (str "non-terminal ticket " (pr-str id)
                       " (status " (pr-str status)
                       ") is inside archive/")}])))

(def ^:private required-fields [:id :title :status])

(defn- blank-string? [v]
  (and (string? v) (str/blank? v)))

(defn- check-required-fields
  "Per-ticket: id, title, status must be present and non-blank."
  [_ctx ticket]
  (let [fm (:frontmatter ticket)
        id (:id fm)]
    (vec (for [field required-fields
               :let  [v (get fm field)]
               :when (or (nil? v) (blank-string? v))]
           (cond-> {:severity :error
                    :code     :missing_required_field
                    :ids      (if id [id] [])
                    :field    field
                    :message  (str "missing required field :" (name field))}
             (and (= field :id) (:path ticket))
             (assoc :path (:path ticket)))))))

(defn- unknown-id-issue
  "Build an :unknown_id error issue for a holder referencing a missing target."
  [holder-id field target]
  {:severity :error
   :code     :unknown_id
   :ids      [holder-id]
   :field    field
   :value    target
   :message  (str "unknown id " (pr-str target) " referenced by "
                  (pr-str holder-id) " via :" (name field))})

(defn- check-unknown-id
  "Per-ticket: every id named in :deps, :links, or :parent must resolve
   to a known ticket. Issues are owned by the holder; the missing target
   is named in :message. Skipped when the holder has no :id —
   :missing_required_field already surfaces that, and an :ids of `[nil]`
   would violate the JSON contract."
  [{:keys [all-ids]} ticket]
  (let [{:keys [id deps links parent]} (:frontmatter ticket)]
    (when id
      (let [all-ids (or all-ids #{})
            missing (fn [field xs]
                      (for [target (cond
                                     (sequential? xs) xs
                                     (some? xs)       [xs])
                            :when (and (string? target)
                                       (not (contains? all-ids target)))]
                        (unknown-id-issue id field target)))]
        (vec (concat (missing :deps   deps)
                     (missing :links  links)
                     (missing :parent parent)))))))

(defn- ac-entry-issue
  "Build an `:acceptance_invalid` issue for one offending entry. `field`
   names the violated key (`:entry`, `:title`, `:done`); `value` is the
   user-visible value that failed; `index` lets the message point at
   the position in the list."
  [holder-id field value index reason]
  {:severity :error
   :code     :acceptance_invalid
   :ids      [holder-id]
   :field    field
   :value    value
   :message  (str "invalid acceptance entry at index " index
                  " (field :" (name field) "): " reason)})

(defn- check-acceptance
  "Per-ticket: validate `:acceptance` shape. Optional field; absent or
   nil is a no-op. Required: a sequential collection of maps, each
   carrying a non-blank string `:title` and a boolean `:done`."
  [_ctx ticket]
  (let [{:keys [id acceptance]} (:frontmatter ticket)]
    (cond
      (nil? acceptance) []

      (not (sequential? acceptance))
      [{:severity :error
        :code     :acceptance_invalid
        :ids      (if id [id] [])
        :field    :acceptance
        :value    acceptance
        :message  (str "invalid :acceptance shape: expected a list of "
                       "{title done} entries, got " (pr-str (type acceptance)))}]

      :else
      (vec (mapcat (fn [entry idx]
                     (cond
                       (not (map? entry))
                       [(ac-entry-issue id :entry entry idx
                                        "expected a {title done} map")]

                       :else
                       (let [{:keys [title done]} entry
                             title-bad? (or (not (string? title))
                                            (str/blank? title))
                             done-bad?  (not (boolean? done))]
                         (cond-> []
                           title-bad?
                           (conj (ac-entry-issue id :title title idx
                                                 "expected a non-blank string"))
                           done-bad?
                           (conj (ac-entry-issue id :done done idx
                                                 "expected a boolean"))))))
                   acceptance
                   (range))))))

(def ^:private per-ticket-validators
  "Functions of `[ctx ticket]` -> seq of issues. `ctx` carries
   `:config` (merged) and `:all-ids` (set of every known id)."
  [check-status check-type check-mode check-priority
   check-required-fields check-terminal-outside-archive
   check-unknown-id check-acceptance])

(defn- per-ticket-issues
  "Run every per-ticket validator against every ticket. When `ids-filter`
   is non-empty, only tickets whose id is in the filter contribute (the
   id-list narrows the per-ticket tier; globals are unaffected)."
  [ctx tickets]
  (let [ids-filter (:ids-filter ctx)
        keep?      (if (seq ids-filter)
                     (fn [t] (contains? ids-filter (get-in t [:frontmatter :id])))
                     (constantly true))]
    (vec (mapcat (fn [t]
                   (when (keep? t)
                     (mapcat #(% ctx t) per-ticket-validators)))
                 tickets))))

(defn- severity-rank
  "Sort helper: 0 for :error, 1 for :warning. errors first. Unknown
   severities sort last (rank 9) — intentional: keeps the total order
   total even if a future code slips a stray severity past
   `validate-filter-spec`'s closed enum."
  [severity]
  (case severity :error 0 :warning 1 9))

(defn- issue-sort-key
  "Total order: severity desc -> code asc -> first-id asc -> message asc."
  [{:keys [severity code ids message]}]
  [(severity-rank severity)
   (name code)
   (or (first ids) "")
   (or message "")])

(defn- active-status-issues
  "Global: surface `config/active-status-issue` (when non-nil) as a
   :invalid_active_status error issue."
  [config]
  (when-let [issue (and (seq config) (config/active-status-issue config))]
    [{:severity :error
      :code     :invalid_active_status
      :ids      []
      :message  (:message issue)}]))

(defn- collect-all-ids
  "Set of every ticket id across the input. Used for unknown_id checks."
  [tickets]
  (into #{} (keep #(get-in % [:frontmatter :id])) tickets))

(defn- parse-error-issues
  "Convert each `{:path :message}` in `parse-errors` into a per-ticket
   :frontmatter_parse_error error issue."
  [parse-errors]
  (mapv (fn [{:keys [path message]}]
          {:severity :error
           :code     :frontmatter_parse_error
           :ids      []
           :path     path
           :message  (str "frontmatter parse error at " path
                          (when message (str ": " message)))})
        (or parse-errors [])))

(def ^:private known-severities
  "Closed enum: severity is a fixed set, unknown values are rejected."
  #{:error :warning})

(defn validate-filter-spec
  "Validate a filter spec `{:severity #{...} :code #{...}}`. Severity is
   a closed enum (rejected on unknown values); :code is open. Returns
   nil on success, or `{:error <human-readable message>}` on failure."
  [{:keys [severity] :as spec}]
  (when spec
    (let [bad (when severity (remove known-severities severity))]
      (when (seq bad)
        {:error (str "unknown severity: "
                     (str/join ", " (map name (sort bad)))
                     "; valid: "
                     (str/join ", " (map name (sort known-severities))))}))))

(defn filter-issues
  "Filter `issues` by spec `{:severity #{...} :code #{...}}`. OR within
   each set, AND across sets. nil/empty spec passes everything through."
  [issues {:keys [severity code]}]
  (let [match-set (fn [allowed-set v]
                    (or (nil? allowed-set)
                        (empty? allowed-set)
                        (contains? allowed-set v)))]
    (filterv (fn [i]
               (and (match-set severity (:severity i))
                    (match-set code     (:code     i))))
             issues)))

(def ^:private archive-subdir "archive")

(defn- try-load-file
  "Tolerantly load one ticket file. On success returns
   `{:ok? true :ticket {:frontmatter ... :body ... :path <s> :archived? <bool>}}`;
   on parse failure returns `{:ok? false :error {:path <s> :message <s>}}`."
  [path archived?]
  (try
    (let [parsed (ticket/parse (slurp (str path)))]
      {:ok?    true
       :ticket (assoc parsed
                      :path      (str path)
                      :archived? archived?)})
    (catch Exception e
      {:ok?   false
       :error {:path    (str path)
               :message (or (.getMessage e) (.toString e))}})))

(defn scan
  "Tolerant per-file loader. Walks `<project-root>/<tickets-dir>` and its
   `archive/` subdirectory, parses every `*.md` file individually, and
   collects successes and parse failures separately. Returns
   `{:tickets [...] :parse-errors [...] :scanned {:live n :archive n}}`.
   `:scanned` counts files attempted (parse failures included)."
  [project-root tickets-dir]
  (let [live      (fs/path project-root tickets-dir)
        archive   (fs/path project-root tickets-dir archive-subdir)
        live-glob    (when (fs/directory? live)    (vec (fs/glob live    "*.md")))
        archive-glob (when (fs/directory? archive) (vec (fs/glob archive "*.md")))
        load-each (fn [paths archived?]
                    (mapv #(try-load-file % archived?) paths))
        results   (concat (load-each (or live-glob []) false)
                          (load-each (or archive-glob []) true))]
    {:tickets      (vec (keep #(when (:ok? %) (:ticket %)) results))
     :parse-errors (vec (keep #(when-not (:ok? %) (:error %)) results))
     :scanned      {:live    (count (or live-glob []))
                    :archive (count (or archive-glob []))}}))

(defn run
  "Run integrity checks against an already-loaded project view.
   Inputs (all keys optional except `:tickets`):
     :tickets       — vector of parsed `{:frontmatter ... :body ... :path ...
                      :archived? <bool>}` maps
     :parse-errors  — vector of `{:path <s> :message <s?>}` from the loader
     :config        — merged config with `:statuses` etc.
     :scanned       — `{:live <n> :archive <n>}` to pass through
     :ids-filter    — set of ids to narrow the per-ticket tier; globals
                      always run on the full ticket set
   Returns `{:issues [...] :scanned {...}}`. Issues are always vectors,
   sorted: severity desc, then code, first-id, message ascending."
  [{:keys [tickets parse-errors config scanned ids-filter]}]
  (let [tickets* (or tickets [])
        ctx      {:config     (or config {})
                  :all-ids    (collect-all-ids tickets*)
                  :ids-filter ids-filter}
        issues   (concat (cycle-issues tickets*)
                         (active-status-issues (or config {}))
                         (per-ticket-issues ctx tickets*)
                         (parse-error-issues parse-errors))]
    {:issues  (vec (sort-by issue-sort-key issues))
     :scanned (or scanned {:live 0 :archive 0})}))
