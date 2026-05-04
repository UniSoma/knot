(ns knot.output
  "Renderers: human (markdown/text + ANSI), JSON, dep-tree.
   stdout = data; stderr = warnings/errors."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [knot.ticket :as ticket]))

(defn- ticket-title
  "Read the title from a ticket's frontmatter, falling back to `\"\"` when
   absent. Read sites stay forgiving so an unmigrated or malformed ticket
   degrades gracefully instead of crashing."
  [ticket]
  (or (get-in ticket [:frontmatter :title]) ""))

(defn- inverse-line
  "Format a single inverse entry as `- <id>  <title>` or
   `- <id>  [missing]`."
  [{:keys [id ticket missing?]}]
  (str "- " id "  "
       (cond
         missing?  "[missing]"
         :else     (ticket-title ticket))))

(def ^:private inverse-section-order
  "Canonical render order: Blockers, Blocking, Children, Linked."
  [[:blockers "## Blockers"]
   [:blocking "## Blocking"]
   [:children "## Children"]
   [:linked   "## Linked"]])

(defn- render-inverse-sections
  "Build the trailing inverse-section markdown for `show-text`. Empty
   sections are omitted; sections are separated by a blank line. Returns
   `\"\"` when every section is empty."
  [inverses]
  (let [parts (for [[k header] inverse-section-order
                    :let [entries (get inverses k)]
                    :when (seq entries)]
                (str header "\n\n"
                     (str/join "\n" (map inverse-line entries))
                     "\n"))]
    (if (empty? parts)
      ""
      (str "\n" (str/join "\n" parts)))))

(defn show-text
  "Render a ticket map for the `show` command. Returns a string containing
   the YAML frontmatter, the markdown body, and (when supplied) the four
   computed inverse sections — `## Blockers`, `## Blocking`, `## Children`,
   `## Linked` — appended after the body. Each inverse entry is
   `{:id ... :ticket <full-ticket>}` for a resolved ref or
   `{:id ... :missing? true}` for a broken one. Empty sections are omitted."
  ([ticket]
   (ticket/render ticket))
  ([ticket inverses]
   (str (ticket/render ticket)
        (render-inverse-sections inverses))))

(def ^:private ansi-codes
  "Map of friendly names to ANSI SGR parameters (numbers as strings)."
  {:reset  "0"
   :bold   "1"
   :faint  "2"
   :dim    "2"
   :red    "31"
   :yellow "33"
   :cyan   "36"})

(def ^:private ansi-reset "[0m")

(defn tty?
  "True when stdout is connected to a terminal. Uses System/console which
   returns nil when stdout is piped or redirected."
  []
  (some? (System/console)))

(defn- positive-int
  "Parse `s` as an integer; return it when positive, else nil. Never throws."
  [s]
  (try
    (let [n (Integer/parseInt (str/trim (str s)))]
      (when (pos? n) n))
    (catch Exception _ nil)))

(defn- env-cols
  "Read $COLUMNS as a positive integer; nil if unset/invalid."
  []
  (positive-int (System/getenv "COLUMNS")))

(defn- stty-cols
  "Probe the controlling terminal via `stty size </dev/tty`. Returns the
   column count as a positive integer, or nil when stty is unavailable,
   no controlling tty exists, or the output can't be parsed.
   `stty size` prints `<rows> <cols>`; the second token is what we want."
  []
  (try
    (let [{:keys [exit out]} (deref (p/process ["sh" "-c" "stty size </dev/tty"]
                                               {:out :string :err :string}))]
      (when (zero? exit)
        (some-> out str/trim (str/split #"\s+") second positive-int)))
    (catch Exception _ nil)))

(defn terminal-width
  "Best-effort detection of the current terminal's column count.
   Tries $COLUMNS first, then probes the controlling terminal with
   `stty size`, then falls back to `default` (80). Always returns a
   positive integer."
  ([] (terminal-width 80))
  ([default]
   (or (env-cols) (stty-cols) default)))

(defn color-enabled?
  "Return true when ANSI color output is appropriate.
   `opts` may include :tty?, :no-color?, :no-color-env. Missing keys fall
   back to the actual environment (System/console, NO_COLOR env var). Per
   no-color.org, NO_COLOR disables color when set to any non-empty value."
  ([] (color-enabled? {}))
  ([opts]
   (let [tty?* (if (contains? opts :tty?)
                 (boolean (:tty? opts))
                 (tty?))
         no-color? (boolean (:no-color? opts))
         env (if (contains? opts :no-color-env)
               (:no-color-env opts)
               (System/getenv "NO_COLOR"))
         env-disables? (and (string? env) (not= env ""))]
     (and tty?* (not no-color?) (not env-disables?)))))

(def schema-version
  "v0.3 JSON envelope schema version. Bumped only on shape-incompatible
   changes; new keys (e.g. a future `warnings` slot) are additive and
   do not change this number."
  1)

(defn envelope-str
  "Serialize a v0.3 JSON envelope around `data`. Default shape is
   `{schema_version: 1, ok: true, data: <data>}`.

   With `{:ok? false}`, emits the same shape but with `ok: false` —
   reserved for commands like `knot check` whose `ok` flag mirrors a
   health verdict and so may coexist with a `data` slot. (The
   error-only envelope produced by `error-envelope-str` does NOT carry
   `:data`; this 2-arity extension is the only path to `ok:false +
   data`.)

   With `{:meta {...}}`, the envelope appends a top-level `:meta` slot
   after `:data` carrying operation metadata (e.g. `{:archived_to ...}`
   from `close --json`). Omitted when `:meta` is nil/absent.

   `array-map` keeps the key order stable for snapshot tests."
  ([data]
   (envelope-str data nil))
  ([data {:keys [ok? meta] :or {ok? true}}]
   (json/generate-string
    (cond-> (array-map :schema_version schema-version
                       :ok             ok?
                       :data           data)
      (some? meta) (assoc :meta meta)))))

(defn error-envelope-str
  "Serialize a v0.3 JSON error envelope. `error` is a map with `:code`,
   `:message`, and an optional `:candidates` vector (used by
   `ambiguous_id`). Extra keys on `error` pass through unchanged so
   richer error shapes can land later without changing this helper."
  [error]
  (json/generate-string
   (array-map :schema_version schema-version
              :ok             false
              :error          error)))

(defn- jsonify-ticket
  "Project a `{:frontmatter ... :body ...}` map into the JSON shape used by
   `show --json` and `ls --json`. Keeps frontmatter keys at the top level
   and adds the body as a `body` field. Frontmatter keys are already
   snake_case in the on-disk YAML and pass straight through.
   The frontmatter map is passed through unchanged so the ordered-map type
   produced by `clj-yaml` survives into Cheshire — keys serialize in the
   on-disk order. `(into {} ordered-map)` would silently rebuild as a
   hash-map past 8 entries and scramble the order."
  [{:keys [frontmatter body] :as _ticket} {:keys [include-body?]
                                           :or {include-body? true}}]
  (cond-> frontmatter
    include-body? (assoc :body body)))

(defn- jsonify-inverse-entry
  "Project an inverse-section entry into the JSON shape: resolved entries
   carry `{id, title, status}`; missing entries carry `{id, missing:true}`."
  [{:keys [id ticket missing?]}]
  (if missing?
    {:id id :missing true}
    {:id     id
     :title  (ticket-title ticket)
     :status (get-in ticket [:frontmatter :status])}))

(defn- inverses->json-fields
  [inverses]
  (when inverses
    {:blockers (mapv jsonify-inverse-entry (:blockers inverses))
     :blocking (mapv jsonify-inverse-entry (:blocking inverses))
     :children (mapv jsonify-inverse-entry (:children inverses))
     :linked   (mapv jsonify-inverse-entry (:linked   inverses))}))

(defn show-json
  "Render a ticket map wrapped in the v0.3 success envelope. The ticket
   sits under `:data`; keys inside it are snake_case. With `inverses`,
   adds `blockers`, `blocking`, `children`, `linked` arrays alongside the
   frontmatter under `:data` — entries are `{id, title, status}` for
   resolved refs or `{id, missing:true}` for broken ones."
  ([ticket]
   (envelope-str (jsonify-ticket ticket {:include-body? true})))
  ([ticket inverses]
   (envelope-str
    (merge (jsonify-ticket ticket {:include-body? true})
           (inverses->json-fields inverses)))))

(defn ls-json
  "Render a sequence of ticket maps wrapped in the v0.3 success envelope.
   The ticket array sits under `:data`; keys inside each entry are
   snake_case; the body is omitted to keep list output compact."
  [tickets]
  (envelope-str
   (mapv #(jsonify-ticket % {:include-body? false}) tickets)))

(defn touched-ticket-json
  "Render a v0.3 success envelope around a single post-mutation ticket
   for `--json` on a mutating command. The ticket sits under `:data`
   with full frontmatter + body (snake_case keys). When `:meta` is
   supplied in `opts`, the envelope adds a top-level `:meta` slot for
   operation metadata (e.g. `{:archived_to \"...\"}` from `close`)."
  ([ticket]
   (touched-ticket-json ticket nil))
  ([ticket {:keys [meta]}]
   (envelope-str (jsonify-ticket ticket {:include-body? true})
                 (when meta {:meta meta}))))

(defn touched-tickets-json
  "Render a v0.3 success envelope around an array of post-mutation
   tickets for `--json` on multi-target mutating commands (`link`,
   `unlink`). Body is excluded per entry — same shape as `ls --json`'s
   `:data` — to keep aggregate output compact."
  [tickets]
  (envelope-str
   (mapv #(jsonify-ticket % {:include-body? false}) tickets)))

(defn colorize
  "Wrap `s` in an ANSI SGR sequence built from `codes` when `color?` is true.
   Returns `s` unchanged when `color?` is false or `codes` is empty.
   `codes` is a sequence of keys from `ansi-codes`."
  [color? codes s]
  (if (or (not color?) (empty? codes))
    s
    (let [params (->> codes (keep ansi-codes) (str/join ";"))]
      (if (str/blank? params)
        s
        (str "[" params "m" s ansi-reset)))))

(def ^:private ls-columns
  [{:key :id        :header "ID"       :align :left}
   {:key :status    :header "STATUS"   :align :left}
   {:key :priority  :header "PRI"      :align :right}
   {:key :mode      :header "MODE"     :align :left}
   {:key :type      :header "TYPE"     :align :left}
   {:key :assignee  :header "ASSIGNEE" :align :left}
   {:key :title     :header "TITLE"    :align :left}])

(def ^:private col-sep "  ")
(def ^:private col-sep-len (count col-sep))

(defn- value-of
  "Plain string for a single ls cell — no padding, no color."
  [ticket k]
  (case k
    :title (ticket-title ticket)
    (let [v (get (:frontmatter ticket) k)]
      (if (some? v) (str v) ""))))

(defn- pad
  [s width align]
  (let [padding (max 0 (- width (count s)))
        spaces  (apply str (repeat padding \space))]
    (case align
      :right (str spaces s)
      :left  (str s spaces))))

(defn- truncate
  [s width]
  (if (<= (count s) width) s (subs s 0 width)))

(defn status-role
  "Resolve a ticket status string to a render role based on project config.
   Returns one of `:terminal` (status is in `terminal-statuses`),
   `:active` (status equals `active-status`),
   `:open` (status is the first entry in `statuses` that is neither active
   nor terminal — i.e. the project's intake/todo lane),
   `:other` (any other configured or unknown status).

   Roles, not literal status names, drive color choice in `ls-table` so
   projects with custom `:statuses` (e.g. \"active\" / \"review\") inherit
   the same visual contract as the default `:open`/`:in_progress`/`:closed`
   workflow."
  [status statuses terminal-statuses active-status]
  (let [terminal-set (set terminal-statuses)
        open-status  (->> statuses
                          (remove (fn [s]
                                    (or (= s active-status)
                                        (contains? terminal-set s))))
                          first)]
    (cond
      (contains? terminal-set status) :terminal
      (= status active-status)        :active
      (= status open-status)          :open
      :else                           :other)))

(def ^:private role->codes
  {:terminal [:dim]
   :active   [:yellow]
   :open     [:cyan]
   :other    []})

(defn- color-codes-for
  [k value status-context]
  (case k
    :status   (let [{:keys [statuses terminal-statuses active-status]} status-context]
                (role->codes (status-role value statuses terminal-statuses active-status)))
    :priority (if (= "0" value) [:red :bold] [])
    :mode     [:faint]
    :type     [:faint]
    []))

(defn- node-label
  "Format a single dep-tree node line: `<id>  <title>` (or `[missing]`),
   with a trailing ` ↑` for seen-before? leaves."
  [{:keys [id ticket missing? seen-before?]}]
  (cond
    missing?     (str id "  [missing]")
    seen-before? (str id "  " (ticket-title ticket) " ↑")
    :else        (str id "  " (ticket-title ticket))))

(defn- render-tree-lines
  "Recursively render a dep-tree node into a flat seq of strings using
   box-drawing characters. `prefix` is the cumulative whitespace/`│`
   stack that comes before the connector; `connector` is `├── `, `└── `,
   or `\"\"` for the root. `child-prefix` is what every descendant of
   this node will inherit (no connector yet)."
  [node prefix connector child-prefix]
  (let [head     (str prefix connector (node-label node))
        children (or (:children node) [])
        n        (count children)]
    (cons head
          (mapcat (fn [i c]
                    (let [last? (= i (dec n))
                          c-conn  (if last? "└── " "├── ")
                          c-cp    (if last? "    " "│   ")]
                      (render-tree-lines c child-prefix c-conn
                                         (str child-prefix c-cp))))
                  (range n)
                  children))))

(defn dep-tree-text
  "Render a dep-tree node (from `knot.query/dep-tree`) as ASCII text using
   box-drawing characters. Seen-before nodes are flagged with ` ↑`;
   missing referents render as `<id>  [missing]`."
  [tree]
  (str/join "\n" (render-tree-lines tree "" "" "")))

(defn- jsonify-tree-node
  "Project a dep-tree node into the JSON map shape: `{id, title?, status?,
   missing?, seen_before?, deps?}`. Title/status are derived from the
   embedded ticket and omitted when the node is `:missing?`. `:deps`
   recurses for normal nodes; omitted for missing or seen-before leaves."
  [{:keys [id ticket children missing? seen-before?]}]
  (cond-> {:id id}
    missing?
    (assoc :missing true)

    (and (not missing?) ticket)
    (merge {:title  (ticket-title ticket)
            :status (get-in ticket [:frontmatter :status])})

    seen-before?
    (assoc :seen_before true)

    (and (not missing?) (not seen-before?))
    (assoc :deps (mapv jsonify-tree-node (or children [])))))

(defn dep-tree-json
  "Render a dep-tree node wrapped in the v0.3 success envelope. The
   nested tree sits under `:data`. Each node carries `id`; non-missing
   nodes also carry `title` and `status`; non-leaf nodes carry a `deps`
   array of children. Missing referents add `missing:true` and stop.
   Seen-before? nodes add `seen_before:true` and stop.

   Asymmetry note vs `show-json`: an unknown root id yields
   `{ok:true, data:{id, missing:true}}` here, not a `not_found` error
   envelope. Dep tree is intentionally tolerant of missing roots so
   consumers can discover broken `:deps` refs *via* the parent that
   links to them — the missing leaf is information, not error. JSON
   consumers should branch on `data.missing` distinctly from `ok:false`."
  [tree]
  (envelope-str (jsonify-tree-node tree)))

(defn ls-table
  "Render `tickets` as a fixed-width text table with the columns
     ID  STATUS  PRI  MODE  TYPE  ASSIGNEE  TITLE
   PRI is right-aligned. TITLE is full-width when piped (`:tty? false`)
   and truncated to fit `:width` (default 80) when `:tty? true`.
   Pass `:color? true` to apply ANSI color to data rows; the header row
   is always plain text.

   Status colors are role-based (terminal → :dim, active → :yellow,
   intake/open lane → :cyan). The role is resolved per ticket from
   `:statuses`, `:terminal-statuses`, and `:active-status` so projects
   with custom `:statuses` get matching colors. Defaults match the v0
   schema for callers that do not pass a status context:
     :statuses          [\"open\" \"in_progress\" \"closed\"]
     :terminal-statuses #{\"closed\"}
     :active-status     \"in_progress\""
  [tickets {:keys [color? tty? width statuses terminal-statuses active-status]
            :or {color? false tty? false width 80
                 statuses          ["open" "in_progress" "closed"]
                 terminal-statuses #{"closed"}
                 active-status     "in_progress"}}]
  (let [status-ctx       {:statuses          statuses
                          :terminal-statuses terminal-statuses
                          :active-status     active-status}
        non-title        (vec (butlast ls-columns))
        title-col        (last ls-columns)
        non-title-widths (mapv (fn [{:keys [key header]}]
                                 (apply max (count header)
                                        (map #(count (value-of % key)) tickets)))
                               non-title)
        prefix-width     (+ (apply + non-title-widths)
                            (* col-sep-len (count non-title)))
        natural-title    (apply max (count (:header title-col))
                                (map #(count (value-of % :title)) tickets))
        title-budget     (if tty?
                           (max 0 (- width prefix-width))
                           natural-title)
        widths           (conj non-title-widths title-budget)
        format-cell      (fn [col w v color-on?]
                           (let [t (truncate v w)
                                 p (pad t w (:align col))]
                             (if color-on?
                               (colorize color?
                                         (color-codes-for (:key col) t status-ctx)
                                         p)
                               p)))
        format-row       (fn [color-on? raw-row]
                           (str/join col-sep
                                     (map (fn [col w v]
                                            (format-cell col w v color-on?))
                                          ls-columns widths raw-row)))
        header-row (format-row false (mapv :header ls-columns))
        data-rows  (map (fn [t]
                          (format-row true
                                      (mapv #(value-of t (:key %)) ls-columns)))
                        tickets)]
    (str/join "\n" (cons header-row data-rows))))

(def ^:private check-columns
  "Canonical column order for the human-readable `knot check` table."
  [{:key :severity :header "SEVERITY" :align :left}
   {:key :code     :header "CODE"     :align :left}
   {:key :ids      :header "IDS"      :align :left}
   {:key :message  :header "MESSAGE"  :align :left}])

(defn- issue-cell
  [issue {:keys [key]}]
  (case key
    :severity (name (:severity issue))
    :code     (name (:code     issue))
    :ids      (if (seq (:ids issue))
                (str/join "," (:ids issue))
                "—")
    :message  (let [{:keys [message path field value]} issue
                    parts (cond-> [message]
                            field (conj (str "field=" (name field)
                                             " value=" (pr-str value)))
                            path  (conj (str "path=" path)))]
                (str/join " | " parts))))

(defn check-table-text
  "Render the `knot check` issues table. Columns: SEVERITY CODE IDS MESSAGE.
   `:path`, `:field`, and `:value` are folded into the message cell so
   the table stays narrow. Empty `:ids` (path-only / global issues)
   render as `—`. Returns an empty string when `issues` is empty so the
   caller can collapse table+footer to footer-only."
  [issues]
  (if (empty? issues)
    ""
    (let [rows       (mapv (fn [issue]
                             (mapv #(issue-cell issue %) check-columns))
                           issues)
          header-row (mapv :header check-columns)
          all-rows   (cons header-row rows)
          col-count  (count check-columns)
          col-widths (mapv (fn [i] (apply max 0 (map #(count (nth % i))
                                                     all-rows)))
                           (range col-count))
          fmt-row    (fn [row]
                       (str/join "  " (map (fn [{:keys [align]} w cell]
                                             (pad cell w align))
                                           check-columns col-widths row)))]
      (str/join "\n" (map fmt-row all-rows)))))

(defn check-summary-footer
  "One-line footer summarizing a `knot check` run: counts of
   errors/warnings + scanned counts. Always emitted (table or no table)."
  [issues {:keys [live archive]}]
  (let [errs   (count (filter #(= :error   (:severity %)) issues))
        warns  (count (filter #(= :warning (:severity %)) issues))
        total  (+ errs warns)
        suffix (str " — scanned: live=" live " archive=" archive)]
    (if (zero? total)
      (str "knot check: ok" suffix)
      (str total " issues (" errs " errors, " warns " warnings)" suffix))))

(def ^:private prime-preamble-found
  "Use the `knot` CLI for all ticket reads and writes in this project — don't `cat`, `grep`, or hand-edit files under `.tickets/`. `knot` resolves partial IDs across live+archive and keeps frontmatter consistent.

When the user says... → you do:
  \"what's next?\" / \"what should I work on?\"        → `knot ready`
  \"any pending bugs?\" / \"list bugs\"                → `knot list --type bug`
  \"let's tackle <id>\" / \"start working on <id>\"    → `knot show <id>`, then `knot start <id>`
  \"I'm done\" / \"shipped\" / \"let's close this\"      → `knot close <id> --summary \"...\"`
  \"note that...\" / \"FYI...\" mid-task               → `knot add-note <id> \"...\"`
  \"blocked on <other>\"                            → `knot dep <current> <other>`
  \"what's blocking this?\"                         → `knot dep tree <id>`

Read commands accept `--type`, `--mode`, `--tag`, `--status`, `--assignee` filters — pass the matching flag instead of scanning a bare list.

Don't read `.tickets/<id>--*.md` directly — prefer `knot show <id>`. Don't write to `.tickets/` by hand — `knot create` / `add-note` / `edit` keep frontmatter valid.

For the full reference (lifecycle, graph ops, JSON shapes, partial-id resolution, AFK vs HITL), invoke the `knot` skill.")

(def ^:private prime-preamble-afk
  "You are an autonomous agent picking up unblocked work in this project. Use the `knot` CLI for all ticket reads and writes — don't `cat`, `grep`, or hand-edit files under `.tickets/`.

Autonomous flow:

  knot ready --mode afk --json     enumerate unblocked agent-runnable candidates
  knot show <id>                   confirm scope before claiming
  knot start <id>                  claim
  knot add-note <id> \"<progress>\"  log progress on long runs
  knot close <id> --summary \"...\"  ship — the summary lands in the ticket as a note

Don't pick up `hitl` tickets — those need a human in the loop. The `mode` field is the contract.

For the full reference (lifecycle, graph ops, JSON shapes, partial-id resolution), invoke the `knot` skill.")

(def ^:private prime-preamble-no-project
  "No Knot project was discovered from the current directory. Run `knot init`
in the project root to create a `.knot.edn` config and `.tickets/` directory
before issuing other Knot commands.")

(def ^:private prime-in-progress-nudge
  "Resume here if the user picks up mid-stream.")

(def ^:private prime-ready-nudge
  "If asked \"what's next\", recommend the top entry and confirm before `knot start`.")

(defn- prime-commands-cheatsheet
  "Render the static `## Commands` cheatsheet block, parameterized by
   `active-status` so the `knot start` line names the project's active
   lane. The caller (prime-cmd) supplies the value from config; the
   no-project branch falls back to `(config/defaults)` so this function
   never has to reason about absence."
  [active-status]
  (str
   "knot list                        list live tickets (alias: ls)\n"
   "knot ready [--mode afk]          list non-blocked tickets (filter by mode)\n"
   "knot show <id>                   show one ticket (frontmatter + body)\n"
   "knot create \"<title>\"            create a new ticket\n"
   "knot start <id>                  transition to " active-status "\n"
   "knot close <id> [--summary <s>]  transition to terminal status + auto-archive\n"
   "knot add-note <id> [text]        append a timestamped note"))

(defn- prime-ticket-line
  "Format a ticket as `id  mode  pri  title` for the prime in-progress and
   ready sections. Missing fields render as `-` so columns stay aligned.
   When `:prime-stale?` is truthy on the ticket map (set by the in-progress
   pipeline when `:updated` is older than the staleness threshold), the
   line is prefixed with `[stale] ` so agents notice forgotten work. The
   renderer is whitespace-only — no ANSI codes — because prime output
   is consumed by AI agents and downstream tools, not human terminals."
  [ticket]
  (let [fm     (:frontmatter ticket)
        id     (or (:id fm) "")
        mode   (or (:mode fm) "-")
        pri    (let [p (:priority fm)] (if (some? p) (str p) "-"))
        title  (ticket-title ticket)
        prefix (if (:prime-stale? ticket) "[stale] " "")]
    (str prefix id "  " mode "  " pri "  " title)))

(defn- prime-section
  "Render a `## <header>` section: heading, optional one-line behavioral
   nudge (blank-line separated), the ticket lines, and an optional
   trailing footer block. Empty ticket sequences still emit the heading
   and nudge so the directive is visible even on a quiet project."
  [header nudge tickets footer]
  (let [nudge-block (if (str/blank? nudge) "" (str nudge "\n\n"))
        lines (mapv prime-ticket-line tickets)
        body  (if (seq lines) (str (str/join "\n" lines) "\n") "")
        foot  (if (str/blank? footer) "" (str footer "\n"))]
    (str "## " header "\n\n" nudge-block body foot)))

(defn- prime-recently-closed-line
  "Render a single recently-closed entry as one or two lines: `id  title`,
   then an indented summary line when `:summary` is present and non-blank."
  [{:keys [id title summary]}]
  (let [head (str (or id "") "  " (or title ""))]
    (if (and (some? summary) (not (str/blank? summary)))
      (str head "\n    " summary)
      head)))

(defn- prime-recently-closed-section
  "Render the `## Recently Closed` section. Returns `\"\"` when entries is
   empty or nil so the caller can concatenate unconditionally."
  [entries]
  (if (empty? entries)
    ""
    (str "## Recently Closed\n\n"
         (str/join "\n" (map prime-recently-closed-line entries))
         "\n\n")))

(defn- prime-project-section
  "Render the `## Project` metadata section. Always shows prefix and
   live/archive counts; the `name` line appears only when supplied."
  [{:keys [prefix name live-count archive-count]}]
  (let [name-line (when (and name (not (str/blank? name)))
                    (str "name: " name "\n"))]
    (str "## Project\n\n"
         "prefix: " (or prefix "") "\n"
         (or name-line "")
         "live: " (or live-count 0) "\n"
         "archive: " (or archive-count 0) "\n")))

(defn prime-text
  "Render the directive markdown primer for the `prime` command:
     1. Directive preamble (or `knot init` directive when no project found)
     2. `## Project` metadata
     3. `## In Progress` ticket lines (omitted entirely when no tickets are
        in the project's active lane — empty heading is dead weight on
        every quiet session)
     4. `## Ready` ticket lines (with behavioral nudge and optional footer)
     5. `## Recently Closed` (omitted when no entries are supplied — gives
        agents a 'what shipped lately' view without scrolling the archive)
     6. `## Commands` cheatsheet (the `knot start` line names
        `:active-status`, which the caller threads through from config —
        the no-project branch in `prime-cmd` substitutes `(config/defaults)`)
   Each ticket line is `id  mode  pri  title`. Caller controls sort and
   limit — this function does not reorder or truncate."
  [{:keys [project in-progress ready ready-truncated? ready-remaining
           recently-closed mode active-status]}]
  (let [found? (:found? project)
        ;; Coerce mode through name+lower-case+trim so keywords (`:afk`),
        ;; uppercase (`"AFK"`), and stray whitespace all reach the same
        ;; preamble. Stringly-typed dispatch is a future trap otherwise.
        mode-norm (some-> mode (cond-> (keyword? mode) name)
                          str str/trim str/lower-case)
        preamble (cond
                   (not found?)            prime-preamble-no-project
                   (= mode-norm "afk")     prime-preamble-afk
                   :else                   prime-preamble-found)
        ready-footer (when ready-truncated?
                       (str "... +" (or ready-remaining 0)
                            " more (run `knot ready`)"))
        in-progress-block (when (seq in-progress)
                            (str (prime-section "In Progress"
                                                (when found? prime-in-progress-nudge)
                                                in-progress
                                                nil)
                                 "\n"))
        recently-closed-block (prime-recently-closed-section recently-closed)]
    (str preamble "\n\n"
         (prime-project-section project) "\n"
         in-progress-block
         (prime-section "Ready"
                        (when found? prime-ready-nudge)
                        ready
                        ready-footer) "\n"
         recently-closed-block
         "## Commands\n\n"
         (prime-commands-cheatsheet active-status) "\n")))

(defn- jsonify-prime-ticket
  "Project a ticket into the compact shape used in prime JSON arrays:
   `{id, status, type, priority, mode, assignee, title}`. Body is omitted
   to keep payloads tight; consumers needing the body call `knot show
   <id> --json`. When `:prime-stale?` is truthy on the ticket map, adds
   `\"stale\":true` so JSON consumers can flag forgotten work without
   re-deriving the threshold."
  [ticket]
  (let [fm (:frontmatter ticket)]
    (cond-> {:id (:id fm)
             :title (ticket-title ticket)}
      (:status fm)   (assoc :status (:status fm))
      (:type fm)     (assoc :type (:type fm))
      (some? (:priority fm)) (assoc :priority (:priority fm))
      (:mode fm)     (assoc :mode (:mode fm))
      (:assignee fm) (assoc :assignee (:assignee fm))
      (:updated fm)  (assoc :updated (:updated fm))
      (:created fm)  (assoc :created (:created fm))
      (:prime-stale? ticket) (assoc :stale true))))

(defn- jsonify-prime-project
  "Project the project metadata into a JSON-friendly map with snake_case
   keys. `:name` is omitted entirely when not provided so consumers
   distinguish 'no name' from 'empty name'."
  [{:keys [found? prefix name live-count archive-count]}]
  (cond-> {:found       (boolean found?)
           :prefix      (or prefix "")
           :live_count  (or live-count 0)
           :archive_count (or archive-count 0)}
    (and name (not (str/blank? name))) (assoc :name name)))

(defn- jsonify-recently-closed
  "Project a recently-closed entry (already in `{:id :title :closed
   :summary}` shape) into the snake_case JSON form. Drops `:summary`
   when absent so the JSON shape stays compact."
  [{:keys [id title closed summary]}]
  (cond-> {:id (or id "") :title (or title "")}
    closed                        (assoc :closed closed)
    (and summary
         (not (str/blank? summary))) (assoc :summary summary)))

(defn prime-json
  "Render the actionable subset of prime data wrapped in the v0.3 success
   envelope. Inside `:data`, keys are snake_case: `project`,
   `in_progress`, `ready`, `ready_truncated`, `ready_remaining`,
   `recently_closed`. The preamble and command cheatsheet are omitted —
   JSON consumers are tools that know the schema by definition."
  [{:keys [project in-progress ready ready-truncated? ready-remaining
           recently-closed]}]
  (envelope-str
   {:project          (jsonify-prime-project project)
    :in_progress      (mapv jsonify-prime-ticket in-progress)
    :ready            (mapv jsonify-prime-ticket ready)
    :ready_truncated  (boolean ready-truncated?)
    :ready_remaining  (or ready-remaining 0)
    :recently_closed  (mapv jsonify-recently-closed (or recently-closed []))}))

(defn- info-scalar
  "Format a scalar value for `info-text`: nil → `(none)`, anything else →
   its `str` form. Boolean values are not auto-mapped to yes/no — the
   caller renders `config_present` explicitly."
  [v]
  (if (nil? v) "(none)" (str v)))

(defn- info-list
  "Format a list value as a single comma-separated line preserving order.
   Empty/nil → `(none)`."
  [xs]
  (if (empty? xs) "(none)" (str/join ", " xs)))

(defn- info-yes-no [b] (if b "yes" "no"))

(defn- info-section
  "Render one section as `## <heading>\\n\\n<body>\\n` where `body` is the
   already-joined `Label: value` lines."
  [heading body]
  (str "## " heading "\n\n" body "\n"))

(defn info-text
  "Render the `knot info` payload as plain text (no ANSI). Five fixed
   sections; each value renders on its own `Label: value` line. Unset
   scalars render as `(none)`; `config_present` as `yes`/`no`; lists as
   one-line comma-separated values preserving order."
  [{:keys [project paths defaults allowed_values counts]}]
  (let [{:keys [knot_version name prefix config_present]} project
        {:keys [cwd project_root config_path tickets_dir
                tickets_path archive_path]} paths
        {:keys [default_assignee effective_create_assignee
                default_type default_priority default_mode]} defaults
        {:keys [statuses active_status terminal_statuses types modes
                priority_range]} allowed_values
        {:keys [live_count archive_count total_count]} counts
        project-block  (str/join "\n"
                                 [(str "Knot version: "   (info-scalar knot_version))
                                  (str "Name: "           (info-scalar name))
                                  (str "Prefix: "         (info-scalar prefix))
                                  (str "Config present: " (info-yes-no config_present))])
        paths-block    (str/join "\n"
                                 [(str "CWD: "           (info-scalar cwd))
                                  (str "Project root: "  (info-scalar project_root))
                                  (str "Config path: "   (info-scalar config_path))
                                  (str "Tickets dir: "   (info-scalar tickets_dir))
                                  (str "Tickets path: "  (info-scalar tickets_path))
                                  (str "Archive path: "  (info-scalar archive_path))])
        defaults-block (str/join "\n"
                                 [(str "Default assignee: "          (info-scalar default_assignee))
                                  (str "Effective create assignee: " (info-scalar effective_create_assignee))
                                  (str "Default type: "              (info-scalar default_type))
                                  (str "Default priority: "          (info-scalar default_priority))
                                  (str "Default mode: "              (info-scalar default_mode))])
        allowed-block  (str/join "\n"
                                 [(str "Statuses: "          (info-list statuses))
                                  (str "Active status: "     (info-scalar active_status))
                                  (str "Terminal statuses: " (info-list terminal_statuses))
                                  (str "Types: "             (info-list types))
                                  (str "Modes: "             (info-list modes))
                                  (str "Priority range: "    (:min priority_range) "-" (:max priority_range))])
        counts-block   (str/join "\n"
                                 [(str "Live count: "    (info-scalar live_count))
                                  (str "Archive count: " (info-scalar archive_count))
                                  (str "Total count: "   (info-scalar total_count))])]
    (str (info-section "Project"        project-block) "\n"
         (info-section "Paths"          paths-block) "\n"
         (info-section "Defaults"       defaults-block) "\n"
         (info-section "Allowed Values" allowed-block) "\n"
         (info-section "Counts"         counts-block))))

(defn info-json
  "Wrap the `knot info` payload in the v0.3 success envelope with the
   data map nested per section."
  [data]
  (envelope-str data))
