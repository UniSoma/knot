(ns knot.check
  "Project-integrity validation. Walks tickets + config and emits a
   sorted vector of issue records, each
   `{:severity :error|:warning :code <kw> :ids [<id>...] :message <s>
     :path? <s> :field? <kw> :value? <any>}`. Pure: callers supply
   already-loaded tickets and scan counts."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [knot.acceptance :as acceptance]
            [knot.config :as config]
            [knot.doc :as doc]
            [knot.query :as query]
            [knot.store :as store]
            [knot.ticket :as ticket]
            [knot.version :as version]))

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

(defn- check-legacy-acceptance
  "Per-ticket warning: ticket body still contains a `## Acceptance
   Criteria` section that `migrate-ac` would lift into structured
   frontmatter. Aligned with what `migrate-ac` actually fixes — uses
   `parse-body-section`, so once the body is stripped the warning
   self-clears and the surface is idempotent."
  [_ctx ticket]
  (let [{:keys [id]} (:frontmatter ticket)
        body         (:body ticket)]
    (when (and id (acceptance/parse-body-section body))
      [{:severity :warning
        :code     :legacy_acceptance_section
        :ids      [id]
        :message  (str "legacy '## Acceptance Criteria' body section found; "
                       "run `knot migrate-ac` to lift entries into "
                       "structured frontmatter")}])))

(defn- check-reserved-sections
  "Per-ticket warning: ticket body carries a `## ` heading `show`
   synthesizes from the graph. Stored, it duplicates the field it names
   and drifts from it. One issue per heading found. `Acceptance
   Criteria` is left to `:legacy_acceptance_section`: that one has an
   automatic fix (`migrate-ac`) and these do not — the prose under a
   graph heading is usually narrative, so only a human can decide what
   survives. `Documents` is left to `:legacy_documents_section` for the
   opposite reason: it became reserved after projects could already have
   written one, so it warns under its own code with its own remedy rather
   than under the code for a heading that was always refused."
  [_ctx ticket]
  (let [{:keys [id]} (:frontmatter ticket)]
    (when id
      (for [heading (ticket/reserved-sections (:body ticket))
            :when   (not (#{"Acceptance Criteria" "Documents"} heading))]
        {:severity :warning
         :code     :reserved_section
         :ids      [id]
         :message  (str "reserved '## " heading "' body section found; "
                        "delete it — knot show renders that section from "
                        (ticket/reserved-section-source heading))}))))

(defn- check-duplicate-sections
  "Per-ticket warning: the same `## ` heading appears more than once in
   one body. `body-sections` concatenates the copies rather than
   clobbering, so the duplication is invisible to every reader
   downstream. One issue per repeated heading, however many copies it
   has. No automatic dedup: which copy survives is a judgment only a
   human can make."
  [_ctx ticket]
  (let [{:keys [id]} (:frontmatter ticket)]
    (when id
      (for [heading (ticket/duplicate-sections (:body ticket))]
        {:severity :warning
         :code     :duplicate_section
         :ids      [id]
         :message  (str "duplicate '## " heading "' body section found; "
                        "keep one copy with `knot update --body` — "
                        "git is the undo path")}))))

(defn- check-legacy-documents-section
  "Per-ticket warning: a body that already carried a `## Documents`
   heading before the heading was reserved. Mirrors
   `check-legacy-acceptance` — warning not error, its own code, the
   remedy named, self-clearing once the heading is gone. Erroring would
   break `check` on upgrade for projects that did nothing wrong. The
   remedy is manual: knot ships no conversion command."
  [_ctx ticket]
  (let [{:keys [id]} (:frontmatter ticket)]
    (when (and id (some #{"Documents"} (ticket/reserved-sections (:body ticket))))
      [{:severity :warning
        :code     :legacy_documents_section
        :ids      [id]
        :message  (str "legacy '## Documents' body section found; knot show "
                       "now renders that section from the document corpus — "
                       "remove the heading and re-add its content with "
                       "`knot document add`")}])))

(def ^:private per-ticket-validators
  "Functions of `[ctx ticket]` -> seq of issues. `ctx` carries
   `:config` (merged) and `:all-ids` (set of every known id)."
  [check-status check-type check-mode check-priority
   check-required-fields check-terminal-outside-archive
   check-unknown-id check-acceptance check-legacy-acceptance
   check-reserved-sections check-duplicate-sections
   check-legacy-documents-section])

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

(defn- check-doc-type
  "Per-document: `:type` must appear in `:doc-types`. Deliberately not
   `check-enum` — that reads a scalar field on a TICKET, and its
   `(seq allowed)` guard skips validation entirely on an empty list, which
   is exactly the hole `:doc-types`' default exists to close. Here an empty
   allow-list rejects every type instead."
  [{:keys [config]} doc]
  (let [{:keys [id type]} (:frontmatter doc)
        allowed (:doc-types config)]
    (when-not (contains? (set allowed) type)
      [{:severity :error
        :code     :invalid_doc_type
        :ids      [id]
        :path     (:path doc)
        :message  (str "document " (pr-str id) " has type " (pr-str type)
                       ", not one of " (pr-str (vec allowed)))}])))

(defn- check-doc-placement
  "Per-document, in precedence order. The agreement check runs first: where
   the owner directory and the authoritative `:ticket` field disagree the
   answer is misplacement, whatever either id resolves to. Only when they
   agree can an unresolvable id mean an orphan. Reversing this yields two
   issues with contradictory repairs for one file."
  [{:keys [all-ids]} doc]
  (let [{:keys [id ticket]} (:frontmatter doc)
        dir (:owner-dir doc)]
    (cond
      (not= dir ticket)
      [{:severity :error
        :code     :doc_directory_mismatch
        :ids      [id]
        :path     (:path doc)
        ;; A document with NO :ticket field lands here, not in the
        ;; orphan branch, which is correct — but `(pr-str nil)` renders as
        ;; the literal "nil", so say "has no ticket field" instead.
        :message  (str "document " (pr-str id) " sits under " (pr-str dir)
                       (if (nil? ticket)
                         " but has no ticket field"
                         (str " but its ticket field names " (pr-str ticket))))}]

      (not (contains? all-ids ticket))
      [{:severity :error
        :code     :doc_unknown_ticket
        :ids      [id]
        :path     (:path doc)
        :message  (str "document " (pr-str id) " names ticket " (pr-str ticket)
                       ", which resolves to no ticket")}])))

(def ^:private per-document-validators
  "Functions of `[ctx doc]` -> seq of issues, run over every document."
  [check-doc-type check-doc-placement])

(defn- check-duplicate-doc-ids
  "Whole-corpus: two files claiming one document id. The backstop for the
   ambiguity resolver's worst input — a hand-copy, or a merge conflict
   resolved by keeping both sides."
  [docs]
  (->> docs
       (group-by #(get-in % [:frontmatter :id]))
       (keep (fn [[id group]]
               (when (< 1 (count group))
                 {:severity :error
                  :code     :duplicate_doc_id
                  :ids      [id]
                  :message  (str "document id " (pr-str id) " is claimed by "
                                 (count group) " files: "
                                 (str/join ", " (sort (map :path group))))})))
       vec))

(defn- per-document-issues
  "Run every per-document validator against every document, plus the one
   whole-corpus arm. Documents are their own tier: `:ids-filter` narrows the
   per-ticket tier and leaves globals alone, and this sits with the globals
   — a document fault is reported wherever it is, so `knot check <id>`
   cannot hide a misfiled document by not naming its directory."
  [ctx docs]
  (concat (mapcat (fn [d] (mapcat #(% ctx d) per-document-validators)) docs)
          (check-duplicate-doc-ids docs)))

(defn- stranded-docs-issues
  "Whole-project warning: documents exist at the default corpus location
   while `:docs-dir` points somewhere that holds none. A warning rather
   than an error because nothing is corrupt and the repair is a judgment
   call: move the files, or fix the key."
  [{:keys [count root]}]
  (when (and count (pos? count))
    [{:severity :warning
      :code     :unreachable_documents
      :ids      []
      :path     root
      :message  (if (= 1 count)
                  (str "1 document at " root " is outside the configured"
                       " :docs-dir, so no knot command can see it; move it"
                       " or correct the key")
                  (str count " documents at " root " are outside the"
                       " configured :docs-dir, so no knot command can see"
                       " them; move them or correct the key"))}]))

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

(def ^:private skill-stamp-re
  #"<!-- installed by knot (\d+\.\d+\.\d+\S*) -->")

(defn skill-stamp
  "The knot version stamped into an installed SKILL.md's `text`, or nil
   when the stamp is missing or unparseable (or `text` is nil)."
  [text]
  (some->> text (re-find skill-stamp-re) second))

(defn skill-staleness
  "How a skill stamped `stamp` relates to the CLI `version`: nil when they
   are equal, else `:older`, `:newer`, or `:missing` for a nil stamp.
   Staleness is plain inequality; the numeric comparison only picks the
   direction for the message."
  [stamp version]
  (cond
    (nil? stamp)      :missing
    (= stamp version) nil
    :else             (let [parts #(mapv parse-long (str/split % #"\."))]
                        (if (neg? (compare (parts stamp) (parts version)))
                          :older
                          :newer))))

(def project-skill-fix
  "How a stale project copy of the skill is fixed."
  "run `knot skill install` and commit")

(defn skill-stale-message
  "One clause naming a stale skill's stamp against the CLI `version`,
   ending with `fix`. Shared by `knot check` and `knot prime`."
  [staleness stamp version fix]
  (str (if (= :missing staleness)
         (str "the installed `knot` skill has a missing or unreadable version stamp (this CLI is "
              version ")")
         (str "the installed `knot` skill is from knot " stamp ", " (name staleness)
              " than this CLI (" version ")"))
       "; " fix))

(defn- skill-stale-issues
  "Global: a :skill_stale warning when the project's installed skill,
   `{:path <SKILL.md> :text <s or nil>}`, is stamped other than `version`.
   nil `skill` means no project copy, which is not an issue."
  [skill version]
  (when skill
    (let [stamp (skill-stamp (:text skill))]
      (when-let [staleness (skill-staleness stamp version)]
        [{:severity :warning
          :code     :skill_stale
          :ids      []
          :path     (:path skill)
          :value    stamp
          :message  (skill-stale-message staleness stamp version project-skill-fix)}]))))

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

(defn- try-load-doc
  "Tolerantly load one document file. Annotates with the path and with the
   owner directory the file was found in — the placement check compares
   that directory against the `:ticket` the file itself claims, so the
   loader is the only place it can be captured."
  [path]
  (try
    (let [parsed (ticket/parse (slurp (str path)))]
      {:ok? true
       :doc (assoc parsed
                   :path      (str path)
                   :owner-dir (str (fs/file-name (fs/parent path))))})
    (catch Exception e
      {:ok?   false
       :error {:path    (str path)
               :message (or (.getMessage e) (.toString e))}})))

(defn scan
  "Tolerant per-file loader. Walks `<project-root>/<tickets-dir>` and its
   `archive/` subdirectory, parses every `*.md` file individually, and
   collects successes and parse failures separately. Documents under
   `docs/<owning-ticket-id>/` are collected into their own slot by a third
   glob: they are markdown with frontmatter too, and without a separate arm
   every one of them would be validated as a malformed ticket.

   A document filename found in the ticket directory or in `archive/` is
   routed to the document slot too, by filename and not by location.
   It is a misplaced document, and diagnosing it as a ticket would report
   a missing status and an out-of-list type — two issues whose repair is
   to add ticket fields to a file that is not a ticket. Classified as a
   document it draws one issue naming the misplacement, which is the
   repair the operator actually needs.

   `:scanned` keeps counting by directory, not by classification: `:live`
   and `:archive` are what those two globs found, misplaced documents
   included, and `:docs` is the `docs/` tree alone. A count of files
   attempted is what an operator can check against `ls`. Returns
   `{:tickets [...] :documents [...] :parse-errors [...]
     :scanned {:live n :archive n :docs n}}`. `docs-root` is the resolved
   document corpus root, which `.knot.edn`'s `:docs-dir` may place outside
   the tickets directory entirely.
   `:scanned` counts files attempted (parse failures included)."
  [project-root tickets-dir docs-root]
  (let [live      (fs/path project-root tickets-dir)
        archive   (fs/path project-root tickets-dir store/archive-subdir)
        docs-root (fs/path docs-root)
        live-glob    (when (fs/directory? live)      (vec (fs/glob live    "*.md")))
        archive-glob (when (fs/directory? archive)   (vec (fs/glob archive "*.md")))
        docs-glob    (when (fs/directory? docs-root) (vec (fs/glob docs-root "*/*.md")))
        ;; `doc/id-of` returns nil for a ticket filename: a ticket id is
        ;; `<prefix>-01…` and the pattern requires `-d` straight after the
        ;; prefix, which `[a-z0-9]+` cannot cross a hyphen to reach.
        doc-file?    (fn [p] (some? (doc/id-of (str (fs/file-name p)))))
        misplaced    (vec (filter doc-file? (concat (or live-glob [])
                                                    (or archive-glob []))))
        load-each (fn [paths archived?]
                    (mapv #(try-load-file % archived?)
                          (remove doc-file? paths)))
        results   (concat (load-each (or live-glob []) false)
                          (load-each (or archive-glob []) true))
        doc-results (mapv try-load-doc (concat (or docs-glob []) misplaced))
        ;; A `:docs-dir` that points somewhere empty makes every document
        ;; surface honestly report nothing. Without this, `check` agrees
        ;; with them and calls the project healthy while the corpus sits
        ;; where nothing will look again. Only the default location is
        ;; probed: it is the one place documents can have been written
        ;; before the key was set or mistyped.
        default-root (fs/path project-root tickets-dir store/docs-subdir)
        stranded     (when (and (not= (str default-root) (str docs-root))
                                (empty? docs-glob)
                                (fs/directory? default-root))
                       (vec (fs/glob default-root "*/*.md")))]
    {:tickets      (vec (keep #(when (:ok? %) (:ticket %)) results))
     :documents    (vec (keep #(when (:ok? %) (:doc %)) doc-results))
     :stranded-docs {:count (count stranded) :root (str default-root)}
     :parse-errors (vec (concat (keep #(when-not (:ok? %) (:error %)) results)
                                (keep #(when-not (:ok? %) (:error %)) doc-results)))
     :scanned      {:live    (count (or live-glob []))
                    :archive (count (or archive-glob []))
                    :docs    (count (or docs-glob []))}}))

(defn run
  "Run integrity checks against an already-loaded project view.
   Inputs (all keys optional except `:tickets`):
     :tickets       — vector of parsed `{:frontmatter ... :body ... :path ...
                      :archived? <bool>}` maps
     :documents     — vector of parsed `{:frontmatter ... :body ... :path ...
                      :owner-dir <s>}` maps, one per document file
     :parse-errors  — vector of `{:path <s> :message <s?>}` from the loader
     :config        — merged config with `:statuses` etc.
     :scanned       — `{:live <n> :archive <n> :docs <n>}` to pass through
     :ids-filter    — set of ids to narrow the per-ticket tier; globals
                      always run on the full ticket set
     :skill         — `{:path :text}` of the project's installed SKILL.md,
                      nil when there is none
     :version       — CLI version the skill stamp must match; defaults to
                      `version/version`
   Returns `{:issues [...] :scanned {...}}`. Issues are always vectors,
   sorted: severity desc, then code, first-id, message ascending."
  [{:keys [tickets documents parse-errors config scanned ids-filter skill
           stranded-docs] :as input}]
  (let [tickets* (or tickets [])
        docs*    (or documents [])
        ctx      {:config     (or config {})
                  :all-ids    (collect-all-ids tickets*)
                  :ids-filter ids-filter}
        issues   (concat (cycle-issues tickets*)
                         (active-status-issues (or config {}))
                         (skill-stale-issues skill (:version input version/version))
                         (stranded-docs-issues stranded-docs)
                         (per-document-issues ctx docs*)
                         (per-ticket-issues ctx tickets*)
                         (parse-error-issues parse-errors))]
    {:issues  (vec (sort-by issue-sort-key issues))
     :scanned (merge {:live 0 :archive 0 :docs 0} scanned)}))
