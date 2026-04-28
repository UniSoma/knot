(ns knot.cli
  "Per-command argument specs and handlers. Wired by knot.main via
   babashka.cli/dispatch."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [flatland.ordered.map :as om]
            [knot.config :as config]
            [knot.git :as git]
            [knot.output :as output]
            [knot.query :as query]
            [knot.store :as store]
            [knot.ticket :as ticket])
  (:import (java.time Instant)))

(defn on-path?
  "True when `cmd` resolves on the current PATH."
  [cmd]
  (some? (fs/which cmd)))

(defn resolve-editor
  "Pick an editor command from `env`: `$VISUAL → $EDITOR → nano → vi`.
   `env` is a map of env-var name → value (typically `(System/getenv)`)."
  [env]
  (let [visual (get env "VISUAL")
        editor (get env "EDITOR")]
    (cond
      (and visual (not (str/blank? visual))) visual
      (and editor (not (str/blank? editor))) editor
      (on-path? "nano")                      "nano"
      :else                                  "vi")))

(defn- now-iso []
  (str (Instant/now)))

(defn- build-body
  "Assemble the markdown body from the title and optional section flags."
  [{:keys [title description design acceptance]}]
  (let [sections
        (cond-> []
          (not (str/blank? description))
          (conj (str "## Description\n\n" description "\n"))

          (not (str/blank? design))
          (conj (str "## Design\n\n" design "\n"))

          (not (str/blank? acceptance))
          (conj (str "## Acceptance Criteria\n\n" acceptance "\n")))
        head (str "# " title "\n")]
    (if (empty? sections)
      (str head "\n")
      (str head "\n" (str/join "\n" sections)))))

(defn- build-frontmatter
  "Build a frontmatter map with a stable, human-readable key order. Keys
   present in this canonical order: id, status, type, priority, mode,
   created, updated, assignee, parent, tags, external_refs. Optional keys
   are omitted when their value is nil/blank/empty."
  [{:keys [id status type priority assignee mode created updated
           tags parent external-ref]}]
  (let [pairs [[:id            id]
               [:status        status]
               [:type          type]
               [:priority      priority]
               [:mode          mode]
               [:created       created]
               [:updated       updated]
               [:assignee      assignee]
               [:parent        parent]
               [:tags          (when (seq tags) (vec tags))]
               [:external_refs (when (seq external-ref) (vec external-ref))]]]
    (into (om/ordered-map)
          (filter (fn [[_ v]] (some? v)))
          pairs)))

(defn- resolve-ctx
  "Fill in defaults and lazy lookups (git user.name, current time) when the
   caller did not provide them. Assignee precedence: explicit ctx value
   wins; else git user.name; else `:default-assignee` from config."
  [ctx]
  (let [defaults (config/defaults)]
    (merge defaults
           {:assignee (when-not (contains? ctx :assignee)
                        (or (git/user-name) (:default-assignee ctx)))}
           ctx
           ;; deterministic 'now' for tests; fall back to wall clock
           (when-not (:now ctx) {:now (now-iso)}))))

(defn create-cmd
  "Create a new ticket from `opts` and write it via `knot.store/save!`.
   Returns the saved path. `ctx` carries `:project-root`, `:prefix`, and
   optional overrides for defaults (`:tickets-dir`, `:default-type`, etc.).
   `opts` is the parsed argument map, e.g. `{:title \"Fix login\" :priority 0}`."
  [ctx opts]
  (let [{:keys [project-root prefix tickets-dir terminal-statuses
                default-type default-priority default-mode now assignee]}
        (resolve-ctx ctx)
        title    (:title opts)
        id       (ticket/generate-id prefix)
        slug     (ticket/derive-slug title)
        fm       (build-frontmatter
                  {:id           id
                   :status       "open"
                   :type         (or (:type opts) default-type)
                   :priority     (or (:priority opts) default-priority)
                   :mode         (or (:mode opts) default-mode)
                   :created      now
                   :updated      now
                   :assignee     (or (:assignee opts) assignee)
                   :tags         (:tags opts)
                   :parent       (:parent opts)
                   :external-ref (:external-ref opts)})
        body     (build-body opts)
        ticket   {:frontmatter fm :body body}]
    (store/save! project-root tickets-dir id slug ticket
                 {:now now :terminal-statuses terminal-statuses})))

(defn- warn!
  "Emit a single line to stderr."
  [msg]
  (binding [*out* *err*] (println msg)))

(defn- warn-broken-refs!
  "Print one stderr warning per broken `:deps` or `:parent` reference on
   the loaded ticket, framed by the source ticket's id for context. No
   output when there are no broken refs."
  [loaded all-tickets]
  (let [src-id (get-in loaded [:frontmatter :id])]
    (doseq [{:keys [kind id]} (query/broken-refs loaded all-tickets)]
      (warn! (str "knot: " src-id ": " (name kind)
                  " reference " id " is missing")))))

(defn show-cmd
  "Load the ticket whose id is `(:id opts)` from the project's tickets-dir
   and return its rendered text. With `:json? true`, returns a bare JSON
   object instead. Returns nil when no matching ticket exists. Output
   includes the four computed inverse sections — Blockers, Blocking,
   Children, Linked — for both human and JSON modes. Broken
   `:deps`/`:parent` references emit one stderr warning each — they
   never abort the command."
  [ctx opts]
  (let [{:keys [project-root tickets-dir]} (resolve-ctx ctx)
        loaded (store/load-one project-root tickets-dir (:id opts))]
    (when loaded
      (let [all       (store/load-all project-root tickets-dir)
            inverses* (query/inverses loaded all)]
        (warn-broken-refs! loaded all)
        (if (:json? opts)
          (output/show-json loaded inverses*)
          (output/show-text loaded inverses*))))))

(defn- first-terminal-status
  "Return the first status from `statuses` that is also in `terminal-statuses`,
   or nil when none of the configured statuses is terminal."
  [statuses terminal-statuses]
  (first (filter (set terminal-statuses) statuses)))

(defn status-cmd
  "Transition the ticket whose id is `(:id opts)` to `(:status opts)`.
   Loads the existing ticket, swaps `:status` in frontmatter, and re-saves
   via `knot.store/save!` — which centralizes `:updated`/`:closed`
   stamping and archive auto-move. Returns the saved path, or nil when
   no ticket matches the id.

   When `(:summary opts)` is supplied (any string, including \"\"), the
   target status must be in `:terminal-statuses` — non-terminal targets
   throw before any file is written. A non-blank summary is appended as
   a timestamped note under `## Notes` via `ticket/append-note`, sharing
   the same writer path as `add-note-cmd`."
  [ctx {:keys [id status summary]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when (and (some? summary)
               (not (contains? (or terminal-statuses #{}) status)))
      (throw (ex-info (str "--summary is only valid on transitions to a "
                           "terminal status; " status " is non-terminal")
                      {:status status})))
    (when-let [loaded (store/load-one project-root tickets-dir id)]
      (let [new-fm  (assoc (:frontmatter loaded) :status status)
            body*   (if (and (some? summary) (not (str/blank? summary)))
                      (ticket/append-note (:body loaded)
                                          now
                                          (str/trim summary))
                      (:body loaded))
            ticket  (assoc loaded :frontmatter new-fm :body body*)]
        (store/save! project-root tickets-dir id nil ticket
                     {:now now :terminal-statuses terminal-statuses})))))

(defn start-cmd
  "Sugar for `status-cmd`: transition `(:id opts)` to `in_progress`."
  [ctx opts]
  (status-cmd ctx (assoc opts :status "in_progress")))

(defn close-cmd
  "Sugar for `status-cmd`: transition `(:id opts)` to the first status from
   the configured `:statuses` list that is also in `:terminal-statuses`.
   Falls back to `closed` when neither key resolves a terminal status."
  [ctx opts]
  (let [{:keys [statuses terminal-statuses]} (resolve-ctx ctx)
        target (or (first-terminal-status statuses terminal-statuses)
                   "closed")]
    (status-cmd ctx (assoc opts :status target))))

(defn reopen-cmd
  "Sugar for `status-cmd`: transition `(:id opts)` to `open`. The
   `:closed` frontmatter key is cleared by `knot.store/save!` because
   `open` is non-terminal."
  [ctx opts]
  (status-cmd ctx (assoc opts :status "open")))

(defn- format-cycle-path
  "Format a cycle path `[a b c a]` as `a → b → c → a` for human messages."
  [cycle]
  (str/join " → " cycle))

(defn dep-cmd
  "Add `(:to opts)` to the `:deps` array of the ticket whose id is
   `(:from opts)`. Idempotent on the deps list (no duplicates). Runs
   cycle detection on the would-be graph before persisting; throws
   `ex-info` with `:cycle` data and a human-readable message when the
   edge would close a cycle. Returns the saved path, or nil when `from`
   does not exist. A non-existent `to` is allowed — broken refs are
   tolerated and surface as `[missing]` markers at render time."
  [ctx {:keys [from to]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (store/load-one project-root tickets-dir from)]
      (let [existing-deps (vec (or (get-in loaded [:frontmatter :deps]) []))
            already?      (some #{to} existing-deps)]
        (when-not already?
          (let [tickets (store/load-all project-root tickets-dir)
                cycle   (query/would-create-cycle? tickets from to)]
            (when cycle
              (throw (ex-info (str "cycle detected: "
                                   (format-cycle-path cycle))
                              {:cycle cycle})))))
        (let [new-deps (if already? existing-deps (conj existing-deps to))
              new-fm   (assoc (:frontmatter loaded) :deps new-deps)
              ticket*  (assoc loaded :frontmatter new-fm)]
          (store/save! project-root tickets-dir from nil ticket*
                       {:now now :terminal-statuses terminal-statuses}))))))

(defn undep-cmd
  "Remove `(:to opts)` from the `:deps` array of the ticket whose id is
   `(:from opts)`. Idempotent: removing a non-present dep is a no-op
   (still bumps `:updated`). When the resulting deps array is empty,
   the `:deps` key is dropped from frontmatter so the on-disk file
   stays clean. Returns the saved path, or nil when `from` does not
   exist."
  [ctx {:keys [from to]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (store/load-one project-root tickets-dir from)]
      (let [existing (vec (or (get-in loaded [:frontmatter :deps]) []))
            kept     (vec (remove #{to} existing))
            new-fm   (if (empty? kept)
                       (dissoc (:frontmatter loaded) :deps)
                       (assoc (:frontmatter loaded) :deps kept))
            ticket*  (assoc loaded :frontmatter new-fm)]
        (store/save! project-root tickets-dir from nil ticket*
                     {:now now :terminal-statuses terminal-statuses})))))

(defn- add-link
  "Add `other` to `ticket`'s `:links`, idempotent. Returns the updated
   ticket; nil-safe on missing `:links`."
  [ticket other]
  (let [existing (vec (or (get-in ticket [:frontmatter :links]) []))]
    (if (some #{other} existing)
      ticket
      (assoc-in ticket [:frontmatter :links] (conj existing other)))))

(defn link-cmd
  "Create symmetric `:links` between every pair of ids in `(:ids opts)`.
   Requires two or more ids. Each id must resolve to an existing ticket;
   a missing id raises an `ex-info` naming it. Idempotent on each side
   — re-linking is a no-op (no duplicate entries). Returns a vector of
   the saved paths, one per modified ticket, in the order ids appear."
  [ctx {:keys [ids]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)
        ids* (vec ids)]
    (when (< (count ids*) 2)
      (throw (ex-info "link requires two or more ticket ids"
                      {:ids ids*})))
    (let [loaded (reduce
                  (fn [acc id]
                    (if-let [t (store/load-one project-root tickets-dir id)]
                      (assoc acc id t)
                      (throw (ex-info (str "no ticket matching " id)
                                      {:id id}))))
                  {}
                  ids*)
          updated (reduce
                   (fn [acc id]
                     (let [others (remove #{id} ids*)
                           t      (reduce add-link (get acc id) others)]
                       (assoc acc id t)))
                   loaded
                   ids*)]
      (mapv (fn [id]
              (store/save! project-root tickets-dir id nil
                           (get updated id)
                           {:now now :terminal-statuses terminal-statuses}))
            ids*))))

(defn- remove-link
  "Remove `other` from `ticket`'s `:links`. When the resulting list is
   empty, drop the `:links` key entirely (mirrors `undep-cmd`'s on-disk
   cleanliness rule)."
  [ticket other]
  (let [existing (vec (or (get-in ticket [:frontmatter :links]) []))
        kept     (vec (remove #{other} existing))]
    (if (empty? kept)
      (update ticket :frontmatter dissoc :links)
      (assoc-in ticket [:frontmatter :links] kept))))

(defn unlink-cmd
  "Remove the symmetric link between `(:from opts)` and `(:to opts)`.
   `from` must resolve to an existing ticket; a missing `from` raises
   `ex-info`. A missing `to` is tolerated — the removal from `from`'s
   `:links` still happens, and the (non-existent) other side is a no-op.
   Idempotent: removing a non-present link is a clean no-op (still bumps
   `:updated`). Returns a vector of saved paths (1 when only `from`
   exists, 2 when both exist)."
  [ctx {:keys [from to]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)
        save-opts {:now now :terminal-statuses terminal-statuses}
        from-t (store/load-one project-root tickets-dir from)]
    (when-not from-t
      (throw (ex-info (str "no ticket matching " from) {:id from})))
    (let [to-t      (store/load-one project-root tickets-dir to)
          from-saved (store/save! project-root tickets-dir from nil
                                  (remove-link from-t to)
                                  save-opts)]
      (if to-t
        [from-saved
         (store/save! project-root tickets-dir to nil
                      (remove-link to-t from)
                      save-opts)]
        [from-saved]))))

(defn- resolve-note-content
  "Resolve the note content string from the layered input options:
   explicit `:text` arg wins; if absent and `:stdin-tty?` is false, call
   `:stdin-reader-fn`; otherwise call `:editor-fn` with a context line.
   Missing fns at the chosen branch return an empty string."
  [{:keys [text stdin-tty? stdin-reader-fn editor-fn] :as _opts} ctx-line]
  (cond
    (some? text)         text
    (false? stdin-tty?)  (if stdin-reader-fn (stdin-reader-fn) "")
    :else                (if editor-fn (editor-fn ctx-line) "")))

(defn add-note-cmd
  "Append a timestamped note to the body of the ticket with id `(:id opts)`.
   Layered input: explicit `:text` wins; otherwise reads stdin (when
   `:stdin-tty?` is false) via `(:stdin-reader-fn)`; otherwise opens the
   editor via `(:editor-fn ctx-line)`. Empty/blank content is a no-op:
   no file change, returns nil. Missing id returns nil. On success
   returns the saved path."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (store/load-one project-root tickets-dir (:id opts))]
      (let [ctx-line (str "Adding a note to " (:id opts) ".")
            content  (resolve-note-content opts ctx-line)]
        (when-not (str/blank? content)
          (let [trimmed (str/trim content)
                body*   (ticket/append-note (:body loaded) now trimmed)
                ticket* (assoc loaded :body body*)]
            (store/save! project-root tickets-dir (:id opts) nil ticket*
                         {:now now :terminal-statuses terminal-statuses})))))))

(defn edit-cmd
  "Open the ticket file for `(:id opts)` in the editor via `(:editor-fn path)`.
   After the editor returns, the file is reloaded and re-saved through
   `knot.store/save!` so `:updated` bumps (even on no-op edits) and any
   status-edit triggers archive routing. The slug suffix is preserved —
   `save!` recovers it from the existing filename. Returns the saved path,
   or nil when no ticket matches the id."
  [ctx {:keys [id editor-fn]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)
        existing-path (store/find-existing-path project-root tickets-dir id)]
    (when existing-path
      (editor-fn existing-path)
      (let [reloaded (ticket/parse (slurp existing-path))]
        (store/save! project-root tickets-dir id nil reloaded
                     {:now now :terminal-statuses terminal-statuses})))))

(defn- filter-criteria
  "Project the filter-relevant keys out of `opts` into the criteria map
   accepted by `query/filter-tickets`. Empty/nil values are dropped so the
   primitive treats absent flags as no-filter."
  [opts]
  (into {}
        (keep (fn [k]
                (when-let [v (get opts k)]
                  (when (seq v) [k v]))))
        [:status :assignee :tag :type :mode]))

(defn ls-cmd
  "List live tickets — those whose status is not in `:terminal-statuses`.
   With `:json? true`, returns a bare JSON array. Otherwise returns the
   rendered text table. Pass `:tty?` and `:color?` to control the table
   format; pass `:width` to constrain TITLE truncation when on a TTY.

   Filter flags `:status`, `:assignee`, `:tag`, `:type`, `:mode` (each a
   set of strings) compose via `query/filter-tickets`. An explicit
   `:status` set replaces the default non-terminal filter — so
   `--status closed` surfaces archived tickets."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses]} (resolve-ctx ctx)
        all      (store/load-all project-root tickets-dir)
        criteria (filter-criteria opts)
        base     (if (contains? criteria :status)
                   all
                   (query/non-terminal all terminal-statuses))
        visible  (query/filter-tickets base criteria)]
    (if (:json? opts)
      (output/ls-json visible)
      (output/ls-table visible (select-keys opts [:tty? :color? :width])))))

(defn- tree-tickets
  "Walk a dep-tree node and return the unique full tickets it contains, in
   pre-order. Skips `:missing?` and `:seen-before?` leaves — missing nodes
   carry no ticket, and seen-before? leaves are duplicates of earlier
   nodes that we've already visited."
  [tree]
  (->> (tree-seq #(seq (:children %)) :children tree)
       (remove :missing?)
       (remove :seen-before?)
       (keep :ticket)
       distinct))

(defn dep-tree-cmd
  "Render the deps subtree rooted at `(:id opts)`. Default mode dedupes
   already-seen branches with `↑` markers; with `:full? true`, every
   occurrence is expanded fully (only true cycles are broken with `↑`
   to prevent infinite recursion). With `:json? true`, returns a bare
   nested JSON object. A missing root id yields a single `[missing]`
   line — `dep-tree-cmd` always returns a string. Broken refs encountered
   anywhere in the rendered subtree emit one stderr warning per source
   ticket, mirroring `show-cmd`."
  [ctx {:keys [id full? json?]}]
  (let [{:keys [project-root tickets-dir]} (resolve-ctx ctx)
        all  (store/load-all project-root tickets-dir)
        tree (query/dep-tree all id {:full? (boolean full?)})]
    (doseq [t (tree-tickets tree)]
      (warn-broken-refs! t all))
    (if json?
      (output/dep-tree-json tree)
      (output/dep-tree-text tree))))

(defn dep-cycle-cmd
  "Project-wide DFS scan for cycles in the deps graph, restricted to
   non-terminal tickets (so cycles that exist only among archived
   tickets don't generate noise). Returns a vector of cycle paths;
   each path is a vector of ids that begins and ends at the same id.
   Empty when no cycles. Caller decides exit code and stderr output."
  [ctx _opts]
  (let [{:keys [project-root tickets-dir terminal-statuses]} (resolve-ctx ctx)
        all  (store/load-all project-root tickets-dir)
        live (query/non-terminal all terminal-statuses)]
    (vec (query/project-cycles live))))

(defn- apply-limit
  "Take the first `n` items of `xs` when `n` is a positive integer. `nil`
   means no limit — return `xs` unchanged. Any other value (including 0
   and negatives) throws: `--limit 0` silently meaning 'no limit' surprised
   users coming from CLIs where 0 means 'zero results'."
  [xs n]
  (cond
    (nil? n)                    xs
    (and (integer? n) (pos? n)) (vec (take n xs))
    :else
    (throw (ex-info (str "--limit must be a positive integer; got " n)
                    {:limit n}))))

(defn ready-cmd
  "List tickets that are non-terminal AND whose `:deps` are all in
   terminal status. With `:json? true`, returns a bare JSON array.
   Otherwise returns the rendered text table. Pass `:tty?`/`:color?`
   to control table formatting (same conventions as `ls-cmd`).

   Filter flags `:status`, `:assignee`, `:tag`, `:type`, `:mode` (each a
   set of strings) compose via `query/filter-tickets`. Filters apply
   BEFORE `:limit` truncation so `--mode afk --limit 5` returns up to
   five afk-mode ready tickets, not five from the unfiltered set."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses]} (resolve-ctx ctx)
        all      (store/load-all project-root tickets-dir)
        ready*   (query/ready all terminal-statuses)
        filtered (query/filter-tickets ready* (filter-criteria opts))
        result   (apply-limit filtered (:limit opts))]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (select-keys opts [:tty? :color? :width])))))

(defn- closed?
  "True when the ticket's `:status` is in `terminal-statuses`."
  [terminal-statuses t]
  (contains? (or terminal-statuses #{})
             (get-in t [:frontmatter :status])))

(defn- by-closed-desc
  "Sort comparator: tickets with a `:closed` timestamp first (newest to
   oldest), then tickets without a stamp last in stable input order."
  [a b]
  (let [ca (get-in a [:frontmatter :closed])
        cb (get-in b [:frontmatter :closed])]
    (cond
      (and ca cb)       (compare cb ca)
      (and ca (nil? cb)) -1
      (and cb (nil? ca)) 1
      :else              0)))

(defn closed-cmd
  "List terminal-status (closed) tickets, sorted by `:closed` descending —
   newest first. Optional `:limit` truncates after the sort. With
   `:json? true`, returns a bare JSON array; otherwise a rendered text
   table. Tickets missing a `:closed` stamp sort last."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses]} (resolve-ctx ctx)
        all      (store/load-all project-root tickets-dir)
        terminal (filter (partial closed? terminal-statuses) all)
        sorted   (sort by-closed-desc terminal)
        result   (apply-limit sorted (:limit opts))]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (select-keys opts [:tty? :color? :width])))))

(defn blocked-cmd
  "List non-terminal tickets that have at least one non-terminal `:deps`
   entry (or a missing referent). With `:json? true`, returns a bare
   JSON array. Otherwise returns the rendered text table."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses]} (resolve-ctx ctx)
        all     (store/load-all project-root tickets-dir)
        result  (query/blocked all terminal-statuses)]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (select-keys opts [:tty? :color? :width])))))

(def ^:private prime-default-limit 20)

(defn- in-progress-tickets
  "Pick non-terminal tickets with status `in_progress` and sort by
   `:updated` descending so the most-recently-touched work surfaces
   first. Tickets without `:updated` sort last in stable input order."
  [tickets]
  (->> tickets
       (filter (fn [t] (= "in_progress" (get-in t [:frontmatter :status]))))
       (sort-by (fn [t] (or (get-in t [:frontmatter :updated]) ""))
                #(compare %2 %1))
       vec))

(defn- count-archive
  "Count tickets whose status is in `terminal-statuses`."
  [tickets terminal-statuses]
  (count (filter (fn [t] (contains? (or terminal-statuses #{})
                                    (get-in t [:frontmatter :status])))
                 tickets)))

(defn prime-cmd
  "Render the agent context primer for the project. Always returns a
   string — never throws and never returns nil — so the caller can wire
   it into a global Claude Code `SessionStart` hook with confidence.

   `ctx` carries the usual project context plus `:project-found?` (set
   when the walk-up discovery hit a `.knot.edn`/`.tickets/` marker) and
   an optional `:project-name`.
   `opts` supports `:mode` (filter ready section to that mode), `:limit`
   (override the default ready cap of 20), and `:json?` (emit the
   actionable bare-object payload instead of markdown).

   The ready section is filtered by `:mode` BEFORE the cap is applied,
   so `--mode afk --limit 5` yields up to 5 afk-mode ready tickets, not
   5 from the unfiltered set."
  [ctx {:keys [json? mode limit]}]
  (if-not (:project-found? ctx)
    (let [data {:project          {:found? false}
                :in-progress      []
                :ready            []
                :ready-truncated? false
                :ready-remaining  0
                :limit            (or limit prime-default-limit)}]
      (if json?
        (output/prime-json data)
        (output/prime-text data)))
    (let [{:keys [project-root tickets-dir terminal-statuses
                  prefix project-name]} (resolve-ctx ctx)
          all          (store/load-all project-root tickets-dir)
          archive-cnt  (count-archive all terminal-statuses)
          live-cnt     (- (count all) archive-cnt)
          in-progress* (in-progress-tickets all)
          ready*       (query/ready all terminal-statuses)
          mode-filter  (when mode {:mode #{mode}})
          ready-filtered (query/filter-tickets ready* (or mode-filter {}))
          cap          (or limit prime-default-limit)
          ready-shown  (vec (take cap ready-filtered))
          ready-total  (count ready-filtered)
          truncated?   (> ready-total cap)
          remaining    (if truncated? (- ready-total cap) 0)
          data         {:project          {:found?        true
                                           :prefix        prefix
                                           :name          project-name
                                           :live-count    live-cnt
                                           :archive-count archive-cnt}
                        :in-progress      in-progress*
                        :ready            ready-shown
                        :ready-truncated? truncated?
                        :ready-remaining  remaining
                        :limit            cap}]
      (if json?
        (output/prime-json data)
        (output/prime-text data)))))

(defn- stub-config
  "Render a self-documenting `.knot.edn` stub with every known key present
   and inline-commented. `tickets-dir` and `prefix` default to PRD values
   when not overridden via `--tickets-dir` / `--prefix`."
  [{:keys [tickets-dir prefix]}]
  (let [d (config/defaults)
        td (or tickets-dir (:tickets-dir d))
        pr-line (if prefix
                  (str " :prefix \"" prefix "\"")
                  " ;; :prefix \"abc\"           ; auto-derived from project dir name when omitted")]
    (str
     ";; Knot project config. All keys are optional; sensible defaults apply.\n"
     ";; See the project README for the full schema.\n"
     "{\n"
     " ;; Where ticket files live, relative to this file.\n"
     " :tickets-dir \"" td "\"\n"
     "\n"
     " ;; Project shortcode prefixed onto every generated ticket id.\n"
     pr-line "\n"
     "\n"
     " ;; Default assignee on `knot create` when git user.name is unavailable.\n"
     " ;; :default-assignee \"alice\"\n"
     "\n"
     " ;; Default ticket type on `knot create` (must be in :types below).\n"
     " :default-type \"" (:default-type d) "\"\n"
     "\n"
     " ;; Default priority on `knot create` — integer 0..4 (0 = highest).\n"
     " :default-priority " (:default-priority d) "\n"
     "\n"
     " ;; Status workflow, in display order. Edit to add e.g. \"review\".\n"
     " :statuses " (pr-str (:statuses d)) "\n"
     "\n"
     " ;; Statuses that are terminal — files of tickets in these statuses\n"
     " ;; auto-move to <tickets-dir>/archive/.\n"
     " :terminal-statuses " (pr-str (:terminal-statuses d)) "\n"
     "\n"
     " ;; Allowed values for the :type field on each ticket.\n"
     " :types " (pr-str (:types d)) "\n"
     "\n"
     " ;; Allowed values for the :mode field — `afk` is agent-runnable,\n"
     " ;; `hitl` requires a human.\n"
     " :modes " (pr-str (:modes d)) "\n"
     "\n"
     " ;; Default mode on `knot create` (must be in :modes above).\n"
     " :default-mode \"" (:default-mode d) "\"\n"
     "}\n")))

(defn init-cmd
  "Write a self-documenting `.knot.edn` stub at the project root and ensure
   the `:tickets-dir` exists. `ctx` carries `:project-root`. `opts` may
   include `:prefix`, `:tickets-dir`, and `:force`. Without `:force`,
   throws if `.knot.edn` already exists. Validates `:prefix` and
   `:tickets-dir` against the same schema `load-config` uses, so the
   written stub is guaranteed to load cleanly. Returns the written config
   path."
  [ctx opts]
  (let [{:keys [project-root]} ctx
        target (str (fs/path project-root ".knot.edn"))]
    (when (and (fs/exists? target) (not (:force opts)))
      (throw (ex-info (str ".knot.edn already exists at " target
                           " — pass --force to overwrite") {})))
    (config/validate! (merge (config/defaults)
                             (select-keys opts [:prefix :tickets-dir])))
    (let [td (or (:tickets-dir opts) (:tickets-dir (config/defaults)))]
      (fs/create-dirs (fs/path project-root td))
      (spit target (stub-config opts))
      target)))
