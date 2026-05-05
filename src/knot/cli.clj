(ns knot.cli
  "Per-command argument specs and handlers. Wired by knot.main via
   babashka.cli/dispatch."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [flatland.ordered.map :as om]
            [knot.acceptance :as acceptance]
            [knot.check :as check]
            [knot.config :as config]
            [knot.git :as git]
            [knot.output :as output]
            [knot.query :as query]
            [knot.store :as store]
            [knot.ticket :as ticket]
            [knot.version :as version])
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
  "Assemble the markdown body from the optional section flags. The title
   and the acceptance criteria live in frontmatter, not the body — with
   no sections supplied the body is the empty string."
  [{:keys [description design]}]
  (let [sections
        (cond-> []
          (not (str/blank? description))
          (conj (str "## Description\n\n" description "\n"))

          (not (str/blank? design))
          (conj (str "## Design\n\n" design "\n")))]
    (if (empty? sections)
      ""
      (str/join "\n" sections))))

(defn- build-frontmatter
  "Build a frontmatter map with a stable, human-readable key order. Keys
   present in this canonical order: id, title, status, type, priority,
   mode, created, updated, assignee, parent, tags, external_refs,
   acceptance. Optional keys are omitted when their value is
   nil/blank/empty."
  [{:keys [id title status type priority assignee mode created updated
           tags parent external-ref acceptance]}]
  (let [pairs [[:id            id]
               [:title         title]
               [:status        status]
               [:type          type]
               [:priority      priority]
               [:mode          mode]
               [:created       created]
               [:updated       updated]
               [:assignee      assignee]
               [:parent        parent]
               [:tags          (when (seq tags) (vec tags))]
               [:external_refs (when (seq external-ref) (vec external-ref))]
               [:acceptance    (when (seq acceptance) (vec acceptance))]]]
    (into (om/ordered-map)
          (filter (fn [[_ v]] (some? v)))
          pairs)))

(defn- first-intake-status
  "Return the first status from `statuses` that is neither the active
   status nor in `terminal-statuses` — the project's intake lane that
   `knot create` and `knot reopen` should target. Returns nil when no
   such status exists in the configured `:statuses`."
  [statuses active-status terminal-statuses]
  (first (remove (some-fn #{active-status}
                          (set terminal-statuses))
                 statuses)))

(defn- resolve-ctx
  "Fill in defaults and lazy lookups (git user.name, current time) when the
   caller did not provide them. Assignee precedence: explicit ctx
   `:assignee` wins; else `:default-assignee` from config when that key is
   present (even with a nil value, which means \"no default — do not
   consult git\"); else git `user.name`."
  [ctx]
  (let [defaults (config/defaults)]
    (merge defaults
           {:assignee (when-not (contains? ctx :assignee)
                        (if (contains? ctx :default-assignee)
                          (:default-assignee ctx)
                          (git/user-name)))}
           ctx
           ;; deterministic 'now' for tests; fall back to wall clock
           (when-not (:now ctx) {:now (now-iso)}))))

(defn create-cmd
  "Create a new ticket from `opts` and write it via `knot.store/save-new!`.
   Returns the saved path. `ctx` carries `:project-root`, `:prefix`, and
   optional overrides for defaults (`:tickets-dir`, `:default-type`, etc.).
   `opts` is the parsed argument map, e.g. `{:title \"Fix login\" :priority 0}`.
   The id is regenerated on filesystem-level collision (via `save-new!`)
   so concurrent same-ms creates can never silently overwrite each other.

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the new ticket under `:data` instead of the saved path."
  [ctx opts]
  (let [{:keys [project-root prefix tickets-dir statuses active-status
                terminal-statuses default-type default-priority default-mode
                now assignee]}
        (resolve-ctx ctx)
        title    (:title opts)
        slug     (ticket/derive-slug title)
        body     (build-body opts)
        gen-id   #(ticket/generate-id prefix)
        intake   (or (first-intake-status statuses active-status terminal-statuses)
                     "open")
        build-fn (fn [id]
                   {:slug   slug
                    :ticket {:frontmatter
                             (build-frontmatter
                              {:id           id
                               :title        title
                               :status       intake
                               :type         (or (:type opts) default-type)
                               :priority     (or (:priority opts) default-priority)
                               :mode         (or (:mode opts) default-mode)
                               :created      now
                               :updated      now
                               :assignee     (or (:assignee opts) assignee)
                               :tags         (:tags opts)
                               :parent       (:parent opts)
                               :external-ref (:external-ref opts)
                               :acceptance   (acceptance/from-titles
                                              (:acceptance opts))})
                             :body body}})
        saved    (store/save-new! project-root tickets-dir gen-id build-fn
                                  {:now now :terminal-statuses terminal-statuses})]
    (if (:json? opts)
      (let [filename (str (fs/file-name saved))
            id       (or (second (re-matches #"(.+?)(?:--.+)?\.md" filename))
                         filename)]
        (output/touched-ticket-json
         (store/load-one project-root tickets-dir id)))
      saved)))

(defn- warn!
  "Emit a single line to stderr."
  [msg]
  (binding [*out* *err*] (println msg)))

(defn- resolve-or-nil
  "Run `store/resolve-id` and translate a `:not-found` ex-info into nil so
   commands that historically returned nil on missing ids preserve that
   contract. Ambiguous-match exceptions still propagate — the caller has
   no sensible default to substitute. Returns the parsed ticket map on a
   unique resolution."
  [project-root tickets-dir input]
  (try
    (store/resolve-id project-root tickets-dir input)
    (catch clojure.lang.ExceptionInfo e
      (when-not (= :not-found (:kind (ex-data e)))
        (throw e)))))

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
   and return its rendered text. `:id` may be partial — `store/resolve-id`
   handles the layered prefix-matching (full-id, then post-prefix ULID).
   With `:json? true`, returns a bare JSON object instead. Returns nil
   when no matching ticket exists; throws `ex-info` with `:kind :ambiguous`
   when the partial id matches more than one ticket. Output includes the
   four computed inverse sections — Blockers, Blocking, Children, Linked
   — for both human and JSON modes. Broken `:deps`/`:parent` references
   emit one stderr warning each — they never abort the command."
  [ctx opts]
  (let [{:keys [project-root tickets-dir]} (resolve-ctx ctx)
        loaded (resolve-or-nil project-root tickets-dir (:id opts))]
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
  "Transition the ticket whose id is `(:id opts)` (full or partial) to
   `(:status opts)`. The resolver canonicalizes the id before save so
   archive auto-move and slug recovery operate on the full id. Returns
   the saved path, or nil when no ticket matches; throws on ambiguous
   partial ids.

   When `(:summary opts)` is supplied (any string, including \"\"), the
   target status must be in `:terminal-statuses` — non-terminal targets
   throw before any file is written. A non-blank summary is appended as
   a timestamped note under `## Notes` via `ticket/append-note`, sharing
   the same writer path as `add-note-cmd`.

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the post-mutation ticket under `:data` instead of the saved
   path. When the new status is in `:terminal-statuses`, the envelope
   adds `:meta {:archived_to <path>}` so callers do not have to infer
   archive routing. Returns nil when no ticket matches (json mode does
   not change the not-found contract; the handler emits the envelope)."
  [ctx {:keys [id status summary json?]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when (and (some? summary)
               (not (contains? (or terminal-statuses #{}) status)))
      (throw (ex-info (str "--summary is only valid on transitions to a "
                           "terminal status; " status " is non-terminal")
                      {:status status})))
    (when-let [loaded (resolve-or-nil project-root tickets-dir id)]
      (let [full-id (get-in loaded [:frontmatter :id])
            new-fm  (assoc (:frontmatter loaded) :status status)
            body*   (if (and (some? summary) (not (str/blank? summary)))
                      (ticket/append-note (:body loaded)
                                          now
                                          (str/trim summary))
                      (:body loaded))
            ticket  (assoc loaded :frontmatter new-fm :body body*)
            saved   (store/save! project-root tickets-dir full-id nil ticket
                                 {:now now :terminal-statuses terminal-statuses})]
        (if json?
          (let [post            (store/load-one project-root tickets-dir full-id)
                terminal-target? (contains? (or terminal-statuses #{}) status)
                meta-opt        (when terminal-target?
                                  {:meta {:archived_to (str saved)}})]
            (output/touched-ticket-json post meta-opt))
          saved)))))

(defn start-cmd
  "Sugar for `status-cmd`: transition `(:id opts)` to the project's
   active status (`:active-status` from config; defaults to `\"in_progress\"`)."
  [ctx opts]
  (let [{:keys [active-status]} (resolve-ctx ctx)]
    (status-cmd ctx (assoc opts :status active-status))))

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
  "Sugar for `status-cmd`: transition `(:id opts)` to the project's
   intake status — the first entry in `:statuses` that is neither
   `:active-status` nor in `:terminal-statuses`. Falls back to `\"open\"`
   when no such status is configured. The `:closed` frontmatter key is
   cleared by `knot.store/save!` because the intake lane is non-terminal."
  [ctx opts]
  (let [{:keys [statuses active-status terminal-statuses]} (resolve-ctx ctx)
        target (or (first-intake-status statuses active-status terminal-statuses)
                   "open")]
    (status-cmd ctx (assoc opts :status target))))

(defn- format-cycle-path
  "Format a cycle path `[a b c a]` as `a → b → c → a` for human messages."
  [cycle]
  (str/join " → " cycle))

(defn dep-cmd
  "Add `(:to opts)` to the `:deps` array of the ticket whose id is
   `(:from opts)`. Both args may be partial ids: `from` resolves
   strictly (returns nil on no match, throws on ambiguous); `to` resolves
   softly via `try-resolve-id` so a deliberately-broken ref still goes
   in verbatim — broken refs are tolerated and surface as `[missing]`
   markers at render time. Idempotent on the deps list. Runs cycle
   detection on the would-be graph before persisting; throws `ex-info`
   with `:cycle` data when the edge would close a cycle.

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the post-mutation `from` ticket under `:data` instead of
   the saved path."
  [ctx {:keys [from to json?]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (resolve-or-nil project-root tickets-dir from)]
      (let [from-id       (get-in loaded [:frontmatter :id])
            to-id         (store/try-resolve-id project-root tickets-dir to)
            existing-deps (vec (or (get-in loaded [:frontmatter :deps]) []))
            already?      (some #{to-id} existing-deps)]
        (when-not already?
          (let [tickets (store/load-all project-root tickets-dir)
                cycle   (query/would-create-cycle? tickets from-id to-id)]
            (when cycle
              (throw (ex-info (str "cycle detected: "
                                   (format-cycle-path cycle))
                              {:cycle cycle})))))
        (let [new-deps (if already? existing-deps (conj existing-deps to-id))
              new-fm   (assoc (:frontmatter loaded) :deps new-deps)
              ticket*  (assoc loaded :frontmatter new-fm)
              saved    (store/save! project-root tickets-dir from-id nil ticket*
                                    {:now now :terminal-statuses terminal-statuses})]
          (if json?
            (output/touched-ticket-json
             (store/load-one project-root tickets-dir from-id))
            saved))))))

(defn undep-cmd
  "Remove `(:to opts)` from the `:deps` array of the ticket whose id is
   `(:from opts)`. Both args may be partial ids: `from` strict, `to` soft
   (so a stale broken ref can still be undepped by typing it verbatim).
   Idempotent: removing a non-present dep is a no-op (still bumps
   `:updated`). When the resulting deps array is empty, the `:deps` key
   is dropped from frontmatter so the on-disk file stays clean. Returns
   the saved path, or nil when `from` does not exist.

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the post-mutation `from` ticket under `:data` instead."
  [ctx {:keys [from to json?]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (resolve-or-nil project-root tickets-dir from)]
      (let [from-id  (get-in loaded [:frontmatter :id])
            to-id    (store/try-resolve-id project-root tickets-dir to)
            existing (vec (or (get-in loaded [:frontmatter :deps]) []))
            kept     (vec (remove #{to-id} existing))
            new-fm   (if (empty? kept)
                       (dissoc (:frontmatter loaded) :deps)
                       (assoc (:frontmatter loaded) :deps kept))
            ticket*  (assoc loaded :frontmatter new-fm)
            saved    (store/save! project-root tickets-dir from-id nil ticket*
                                  {:now now :terminal-statuses terminal-statuses})]
        (if json?
          (output/touched-ticket-json
           (store/load-one project-root tickets-dir from-id))
          saved)))))

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
   Requires two or more ids. Each id may be partial — `store/resolve-id`
   canonicalizes them. A missing id propagates a `:not-found` ex-info
   with message `\"ticket not found: <input>\"`; an ambiguous partial
   propagates `:ambiguous`. Idempotent on each side — re-linking is a
   no-op (no duplicate entries). Returns a vector of the saved paths,
   one per modified ticket, in the resolved-id order.

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the array of post-mutation tickets under `:data` (body
   excluded, ls-shape) instead of the path vector."
  [ctx {:keys [ids json?]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)
        ids* (vec ids)]
    (when (< (count ids*) 2)
      (throw (ex-info "link requires two or more ticket ids"
                      {:ids ids*})))
    (let [resolved (mapv #(store/resolve-id project-root tickets-dir %) ids*)
          full-ids (mapv #(get-in % [:frontmatter :id]) resolved)
          loaded   (zipmap full-ids resolved)
          updated  (reduce
                    (fn [acc id]
                      (let [others (remove #{id} full-ids)
                            t      (reduce add-link (get acc id) others)]
                        (assoc acc id t)))
                    loaded
                    full-ids)
          paths    (mapv (fn [id]
                           (store/save! project-root tickets-dir id nil
                                        (get updated id)
                                        {:now now :terminal-statuses terminal-statuses}))
                         full-ids)]
      (if json?
        (output/touched-tickets-json
         (mapv #(store/load-one project-root tickets-dir %) full-ids))
        paths))))

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
   Both args may be partial: `from` is resolved strictly (a `:not-found`
   propagates as ex-info); `to` is resolved softly so a previously-broken
   link can still be undone by typing it verbatim. Idempotent: removing
   a non-present link is a clean no-op (still bumps `:updated`). Returns
   a vector of saved paths (1 when only `from` exists, 2 when both exist).

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the array of post-mutation tickets under `:data` (body
   excluded, ls-shape) — 1 entry when only `from` exists, 2 when both."
  [ctx {:keys [from to json?]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)
        save-opts  {:now now :terminal-statuses terminal-statuses}
        from-t     (store/resolve-id project-root tickets-dir from)
        from-id    (get-in from-t [:frontmatter :id])
        to-id      (store/try-resolve-id project-root tickets-dir to)
        to-t       (store/load-one project-root tickets-dir to-id)
        from-saved (store/save! project-root tickets-dir from-id nil
                                (remove-link from-t to-id)
                                save-opts)
        paths      (if to-t
                     [from-saved
                      (store/save! project-root tickets-dir to-id nil
                                   (remove-link to-t from-id)
                                   save-opts)]
                     [from-saved])]
    (if json?
      (let [touched-ids (if to-t [from-id to-id] [from-id])]
        (output/touched-tickets-json
         (mapv #(store/load-one project-root tickets-dir %) touched-ids)))
      paths)))

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
  "Append a timestamped note to the body of the ticket with id `(:id opts)`
   (full or partial). Layered input: explicit `:text` wins; otherwise
   reads stdin (when `:stdin-tty?` is false) via `(:stdin-reader-fn)`;
   otherwise opens the editor via `(:editor-fn ctx-line)`. Empty/blank
   content is a no-op: no file change, returns nil. Missing id returns
   nil; ambiguous id throws. On success returns the saved path.

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the post-mutation ticket (including the appended note)
   under `:data` instead of the saved path. Empty-content cancellation
   and missing-id paths still return nil — the handler emits the
   appropriate envelope."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (resolve-or-nil project-root tickets-dir (:id opts))]
      (let [full-id  (get-in loaded [:frontmatter :id])
            ctx-line (str "Adding a note to " full-id ".")
            content  (resolve-note-content opts ctx-line)]
        (when-not (str/blank? content)
          (let [trimmed (str/trim content)
                body*   (ticket/append-note (:body loaded) now trimmed)
                ticket* (assoc loaded :body body*)
                saved   (store/save! project-root tickets-dir full-id nil ticket*
                                     {:now now :terminal-statuses terminal-statuses})]
            (if (:json? opts)
              (output/touched-ticket-json
               (store/load-one project-root tickets-dir full-id))
              saved)))))))

(defn edit-cmd
  "Open the ticket file for `(:id opts)` (full or partial) in the editor
   via `(:editor-fn path)`. After the editor returns, the file is
   reloaded and re-saved through `knot.store/save!` so `:updated` bumps
   (even on no-op edits) and any status-edit triggers archive routing.
   The slug suffix is preserved — `save!` recovers it from the existing
   filename. Returns the saved path, or nil when no ticket matches the
   id; ambiguous partial ids throw."
  [ctx {:keys [id editor-fn]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [resolved (resolve-or-nil project-root tickets-dir id)]
      (let [full-id       (get-in resolved [:frontmatter :id])
            existing-path (store/find-existing-path project-root tickets-dir full-id)]
        (editor-fn existing-path)
        (let [reloaded (ticket/parse (slurp existing-path))]
          (store/save! project-root tickets-dir full-id nil reloaded
                       {:now now :terminal-statuses terminal-statuses}))))))

(defn- section-region
  "Locate `## <heading>` in `body`. Returns `{:start :end}` where `:end`
   is the start of the next `## ` heading or `(count body)`. Returns nil
   when the heading is missing. Heading-text is regex-quoted so headings
   with regex metacharacters are matched verbatim."
  [body heading]
  (let [pat (re-pattern (str "(?m)^## "
                             (java.util.regex.Pattern/quote heading)
                             "[ \\t]*$"))
        m   (re-matcher pat body)]
    (when (.find m)
      (let [next-m (re-matcher #"(?m)^## " body)
            after  (.end m)
            end    (if (.find next-m after)
                     (.start next-m)
                     (count body))]
        {:start (.start m) :end end}))))

(defn- replace-section
  "Return `body` with the `## <heading>` section replaced by `content`.
   When the section is missing and `content` is non-blank, append it as
   a new section to the end (mirrors `build-body`'s shape). When
   `content` is blank, drop the section if present; otherwise no-op
   (the input is returned verbatim — caller is responsible for the
   shape of bodies that have never been touched). All shapes that
   actually mutate the body normalize to a single trailing newline so
   round-trips through `ticket/render` stay idempotent."
  [body heading content]
  (let [body* (or body "")
        region (section-region body* heading)]
    (cond
      (and (str/blank? content) (nil? region))
      body*

      (str/blank? content)
      (let [{:keys [start end]} region
            head (str/replace (subs body* 0 start) #"\s+$" "")
            tail (subs body* end)]
        (cond
          (str/blank? tail) (if (seq head) (str head "\n") "")
          (seq head)        (str head "\n\n" tail)
          :else             tail))

      (some? region)
      (let [{:keys [start end]} region
            head     (subs body* 0 start)
            tail     (subs body* end)
            sep-tail (if (str/blank? tail) "\n" "\n\n")]
        (str head "## " heading "\n\n" content sep-tail tail))

      :else
      (let [head (str/replace body* #"\s+$" "")
            sep  (if (str/blank? head) "" "\n\n")]
        (str head sep "## " heading "\n\n" content "\n")))))

(defn- update-frontmatter
  "Project `opts` onto `fm`. Keys absent from `opts` leave `fm`
   unchanged; keys present with a non-empty value set the field; keys
   present with a blank string or empty collection clear the field
   (drop the YAML key) for the optional fields (`:assignee`, `:parent`,
   `:tags`, `:external_refs`). The required-ish fields (`:title`,
   `:type`, `:priority`, `:mode`) are set to whatever value the caller
   passed — clearing those is not a sanctioned operation."
  [fm opts]
  (let [{:keys [title type priority mode assignee parent tags external-ref]} opts
        clear-when (fn [m k pred v]
                     (if (pred v) (dissoc m k) (assoc m k v)))]
    (cond-> fm
      (contains? opts :title)        (assoc :title title)
      (contains? opts :type)         (assoc :type type)
      (contains? opts :priority)     (assoc :priority priority)
      (contains? opts :mode)         (assoc :mode mode)
      (contains? opts :assignee)     (clear-when :assignee str/blank? assignee)
      (contains? opts :parent)       (clear-when :parent   str/blank? parent)
      (contains? opts :tags)         (clear-when :tags  empty? (vec tags))
      (contains? opts :external-ref) (clear-when :external_refs
                                                 empty? (vec external-ref)))))

(defn- update-body
  "Apply the body-mutation flags from `opts` to `body`. `--body`
   replaces the whole body; the sectional flags
   (`:description`/`:design`) replace named sections in place (or
   append when missing). `--body` is mutually exclusive with the
   sectional flags — the caller must validate that before calling.
   Acceptance criteria are not body content under v0.3; they live in
   frontmatter and flip via `--ac --done/--undone`."
  [body opts]
  (cond
    (contains? opts :body)
    (:body opts)

    :else
    (cond-> body
      (contains? opts :description) (replace-section "Description" (:description opts))
      (contains? opts :design)      (replace-section "Design" (:design opts)))))

(defn- validate-ac-flip-opts!
  "Validate the `--ac` / `--done` / `--undone` flag triple. `--ac`
   requires exactly one of `--done` or `--undone`; `--done` and
   `--undone` each require `--ac`. Throws `ex-info` on misuse so
   `update-cmd` can surface a clean message."
  [opts]
  (let [ac?     (contains? opts :ac)
        done?   (boolean (:done opts))
        undone? (boolean (:undone opts))]
    (cond
      (and done? undone?)
      (throw (ex-info "--done and --undone are mutually exclusive"
                      {:offending [:done :undone]}))

      (and ac? (not (or done? undone?)))
      (throw (ex-info "--ac requires --done or --undone"
                      {:offending [:ac]}))

      (and (or done? undone?) (not ac?))
      (throw (ex-info (str "--" (if done? "done" "undone")
                           " requires --ac \"<title>\"")
                      {:offending [(if done? :done :undone)]})))))

(defn- apply-ac-flip
  "When `opts` carries `--ac`, flip the matching frontmatter entry on
   `fm`. Returns the (possibly updated) frontmatter. Throws when the
   title does not match any entry — the user named a criterion that
   does not exist."
  [fm opts]
  (if-not (contains? opts :ac)
    fm
    (let [title    (:ac opts)
          done?    (boolean (:done opts))
          flipped  (acceptance/flip (:acceptance fm) title done?)]
      (if flipped
        (assoc fm :acceptance flipped)
        (throw (ex-info (str "no acceptance criterion matching " (pr-str title))
                        {:ac-not-found title}))))))

(defn update-cmd
  "Apply non-interactive updates to the ticket whose id is `(:id opts)`
   (full or partial). Frontmatter flags (`:title`, `:type`, `:priority`,
   `:mode`, `:assignee`, `:parent`, `:tags`, `:external-ref`) set field
   values; sectional body flags (`:description`, `:design`) replace
   those `## ...` sections in place; `:body` replaces the whole body
   and is mutually exclusive with the sectional flags. The acceptance
   triple `(:ac \"<title>\" + :done|:undone)` flips the `:done` state
   of one frontmatter `acceptance` entry; the title must match exactly.
   Optional frontmatter fields (`:assignee`, `:parent`, `:tags`,
   `:external_refs`) are cleared when the supplied value is blank or
   empty. Returns the saved path, or nil when no ticket matches; throws
   on ambiguous partial ids. `:updated` bumps on every successful save
   (re-uses `store/save!`).

   With `:json? true`, returns a v0.3 success-envelope JSON string
   wrapping the post-mutation ticket under `:data` instead of the
   saved path. The `:updated` timestamp inside `:data` reflects the
   bump."
  [ctx opts]
  (when (and (contains? opts :body)
             (some #(contains? opts %) [:description :design]))
    (throw (ex-info (str "--body is mutually exclusive with "
                         "--description / --design")
                    {:offending (filter #(contains? opts %)
                                        [:description :design])})))
  (validate-ac-flip-opts! opts)
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (resolve-or-nil project-root tickets-dir (:id opts))]
      (let [full-id (get-in loaded [:frontmatter :id])
            fm*     (-> (:frontmatter loaded)
                        (update-frontmatter opts)
                        (apply-ac-flip opts))
            body*   (update-body (:body loaded) opts)
            ticket* (assoc loaded :frontmatter fm* :body body*)
            saved   (store/save! project-root tickets-dir full-id nil ticket*
                                 {:now now :terminal-statuses terminal-statuses})]
        (if (:json? opts)
          (output/touched-ticket-json
           (store/load-one project-root tickets-dir full-id))
          saved)))))

(defn- filter-criteria
  "Project the filter-relevant keys out of `opts` into the criteria map
   accepted by `query/filter-tickets`. Empty/nil values are dropped so the
   primitive treats absent flags as no-filter."
  [opts]
  (into {}
        (keep (fn [k]
                (when-let [v (get opts k)]
                  (when (seq v) [k v]))))
        [:status :assignee :tag :type :mode :acceptance-complete]))

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

(defn- ls-table-opts
  "Build the `output/ls-table` options map from CLI `opts` plus the
   resolved-ctx status fields (`:statuses`, `:terminal-statuses`,
   `:active-status`). Threading these through is what lets the table
   color custom-status projects correctly — the renderer otherwise
   falls back to defaults that hide custom :active-status lanes."
  [resolved opts]
  (merge (select-keys opts [:tty? :color? :width])
         (select-keys resolved [:statuses :terminal-statuses :active-status])))

(defn ls-cmd
  "List live tickets — those whose status is not in `:terminal-statuses`.
   With `:json? true`, returns a bare JSON array. Otherwise returns the
   rendered text table. Pass `:tty?` and `:color?` to control the table
   format; pass `:width` to constrain TITLE truncation when on a TTY.

   Filter flags `:status`, `:assignee`, `:tag`, `:type`, `:mode` (each a
   set of strings) compose via `query/filter-tickets`. An explicit
   `:status` set replaces the default non-terminal filter — so
   `--status closed` surfaces archived tickets. `:limit` truncates after
   filtering."
  [ctx opts]
  (let [resolved (resolve-ctx ctx)
        {:keys [project-root tickets-dir terminal-statuses]} resolved
        all      (store/load-all project-root tickets-dir)
        criteria (filter-criteria opts)
        base     (if (contains? criteria :status)
                   all
                   (query/non-terminal all terminal-statuses))
        visible  (query/filter-tickets base criteria)
        result   (apply-limit visible (:limit opts))]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (ls-table-opts resolved opts)))))

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
  "Render the deps subtree rooted at `(:id opts)` (full or partial).
   The root id is resolved softly: a unique partial match is canonicalized
   to the full id; a non-resolving input is passed through verbatim so the
   renderer can emit a `[missing]` line. Ambiguous partial ids propagate
   as ex-info — the user must disambiguate. Default mode dedupes
   already-seen branches with `↑` markers; with `:full? true`, every
   occurrence is expanded fully (only true cycles are broken with `↑`
   to prevent infinite recursion). With `:json? true`, returns a bare
   nested JSON object. Broken refs encountered anywhere in the rendered
   subtree emit one stderr warning per source ticket, mirroring `show-cmd`."
  [ctx {:keys [id full? json?]}]
  (let [{:keys [project-root tickets-dir]} (resolve-ctx ctx)
        full-id (store/try-resolve-id project-root tickets-dir id)
        all     (store/load-all project-root tickets-dir)
        tree    (query/dep-tree all full-id {:full? (boolean full?)})]
    (doseq [t (tree-tickets tree)]
      (warn-broken-refs! t all))
    (if json?
      (output/dep-tree-json tree)
      (output/dep-tree-text tree))))

(defn- jsonify-issue
  "Project an issue map into the JSON-friendly shape: stringify
   `:severity`, `:code`, `:field`; pass through everything else verbatim
   (so future closed-set additions remain forward-compat)."
  [issue]
  (cond-> issue
    (:severity issue) (update :severity name)
    (:code     issue) (update :code     name)
    (:field    issue) (update :field    name)))

(defn- jsonify-result
  "Build the `data` body of the check envelope: sorted issues + scanned counts."
  [{:keys [issues scanned]}]
  (array-map :issues  (mapv jsonify-issue issues)
             :scanned scanned))

(defn- ->kw-set
  "Coerce CLI input (string/keyword set or sequential, or nil) into a
   set of keywords. Used for both --severity and --code; the closed-vs-
   open enum distinction lives in `check/validate-filter-spec`."
  [v]
  (cond
    (nil? v) nil
    (or (set? v) (sequential? v))
    (when (seq v) (set (map #(if (keyword? %) % (keyword %)) v)))
    :else nil))

(defn check-cmd
  "Run `knot check`. `opts` may include:
     :json?    — emit a JSON envelope on stdout instead of the table
     :severity — set/seq of severity values (keyword or string) to keep
     :code     — set/seq of code values (keyword or string) to keep
     :ids      — vector of full ids; narrows the per-ticket tier only

   Returns `{:exit n :stdout s :stderr s}`. `nil` for stdout/stderr means
   no output on that channel. `:exit` is 0 (clean filtered view), 1
   (errors in filtered view), or 2 (invalid filter spec). Cannot-scan
   exit-2 (no project, malformed `.knot.edn`) is the upstream caller's
   responsibility, not this fn's. Globals always run on the full ticket
   set; the id list only narrows the per-ticket tier. The `--severity`
   enum is closed (rejects unknown); `--code` is open (silently passes
   through; matches nothing if unrecognized)."
  [ctx {:keys [json? severity code ids]}]
  (let [resolved (resolve-ctx ctx)
        {:keys [project-root tickets-dir]} resolved
        spec     {:severity (->kw-set severity)
                  :code     (->kw-set code)}]
    (if-let [bad (check/validate-filter-spec spec)]
      ;; Argument-parse errors land on stderr in both modes — matches
      ;; the arg-parsing-stays-on-stderr policy and the bb-cli parse
      ;; path in `main/check-handler`. Cannot-scan errors, by contrast,
      ;; route to stdout as an envelope under --json.
      {:exit 2 :stdout nil :stderr (str "knot check: " (:error bad))}
      (let [{:keys [tickets parse-errors scanned]} (check/scan project-root tickets-dir)
            result    (check/run {:tickets      tickets
                                  :parse-errors parse-errors
                                  :config       resolved
                                  :scanned      scanned
                                  :ids-filter   (when (seq ids) (set ids))})
            filtered  (check/filter-issues (:issues result) spec)
            has-err?  (some #(= :error (:severity %)) filtered)
            scanned*  (:scanned result)
            view      {:issues filtered :scanned scanned*}]
        {:exit   (if has-err? 1 0)
         :stderr nil
         :stdout (if json?
                   (output/envelope-str (jsonify-result view) {:ok? (not has-err?)})
                   (let [table (output/check-table-text filtered)
                         foot  (output/check-summary-footer filtered scanned*)]
                     (if (str/blank? table)
                       foot
                       (str table "\n" foot))))}))))

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
  (let [resolved (resolve-ctx ctx)
        {:keys [project-root tickets-dir terminal-statuses]} resolved
        all      (store/load-all project-root tickets-dir)
        ready*   (query/ready all terminal-statuses)
        filtered (query/filter-tickets ready* (filter-criteria opts))
        result   (apply-limit filtered (:limit opts))]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (ls-table-opts resolved opts)))))

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
   newest first. Optional `:limit` truncates after filter+sort. With
   `:json? true`, returns a bare JSON array; otherwise a rendered text
   table. Tickets missing a `:closed` stamp sort last.

   Filter flags `:status`, `:assignee`, `:tag`, `:type`, `:mode` (each a
   set of strings) compose via `query/filter-tickets`, applied before sort."
  [ctx opts]
  (let [resolved (resolve-ctx ctx)
        {:keys [project-root tickets-dir terminal-statuses]} resolved
        all      (store/load-all project-root tickets-dir)
        terminal (filter (partial closed? terminal-statuses) all)
        filtered (query/filter-tickets terminal (filter-criteria opts))
        sorted   (sort by-closed-desc filtered)
        result   (apply-limit sorted (:limit opts))]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (ls-table-opts resolved opts)))))

(defn blocked-cmd
  "List non-terminal tickets that have at least one non-terminal `:deps`
   entry (or a missing referent). With `:json? true`, returns a bare
   JSON array. Otherwise returns the rendered text table.

   Filter flags `:status`, `:assignee`, `:tag`, `:type`, `:mode` (each a
   set of strings) compose via `query/filter-tickets`, applied after
   computing the blocked set. `:limit` truncates after filtering."
  [ctx opts]
  (let [resolved (resolve-ctx ctx)
        {:keys [project-root tickets-dir terminal-statuses]} resolved
        all      (store/load-all project-root tickets-dir)
        blocked* (query/blocked all terminal-statuses)
        filtered (query/filter-tickets blocked* (filter-criteria opts))
        result   (apply-limit filtered (:limit opts))]
    (if (:json? opts)
      (output/ls-json result)
      (output/ls-table result (ls-table-opts resolved opts)))))

(def ^:private prime-default-limit 20)
(def ^:private prime-recently-closed-limit 3)
(def ^:private prime-stale-days 14)
(def ^:private millis-per-day 86400000)

(defn- parse-instant-ms
  "Parse an ISO 8601 timestamp string to epoch millis. Returns nil for
   nil/blank/unparseable input so the staleness check degrades silently
   on malformed timestamps rather than crashing prime."
  [iso]
  (when (and (string? iso) (not (str/blank? iso)))
    (try
      (.toEpochMilli (Instant/parse iso))
      (catch Exception _ nil))))

(defn- stale-in-progress?
  "True when an in-progress ticket's `:updated` is `prime-stale-days` or
   more older than `now-iso`. Returns false on any nil/unparseable input
   so unmigrated tickets degrade gracefully."
  [ticket now-iso]
  (let [updated (get-in ticket [:frontmatter :updated])
        a (parse-instant-ms updated)
        b (parse-instant-ms now-iso)]
    (boolean
     (when (and a b)
       (>= (- b a) (* prime-stale-days millis-per-day))))))

(defn- recently-closed-tickets
  "Project the top-N most recently closed tickets into the compact shape
   prime renders. Filters by terminal status, sorts by `:closed`
   descending, and extracts the latest body note as `:summary` (typically
   the close --summary). Tickets without `:closed` sort last."
  [tickets terminal-statuses]
  (->> tickets
       (filter (partial closed? terminal-statuses))
       (sort by-closed-desc)
       (take prime-recently-closed-limit)
       (mapv (fn [t]
               (let [fm (:frontmatter t)]
                 {:id      (:id fm)
                  :title   (or (:title fm) "")
                  :closed  (:closed fm)
                  :summary (ticket/latest-note-content (:body t))})))))

(defn- in-progress-tickets
  "Pick non-terminal tickets whose status equals `active-status` (the
   project's active lane) and sort by `:updated` descending so the
   most-recently-touched work surfaces first. Tickets without `:updated`
   sort last in stable input order. Each returned map carries
   `:prime-stale?` — true when `:updated` is `prime-stale-days` or more
   older than `now-iso`. The flag drives the `[stale]` prefix in the
   renderer and the `\"stale\":true` field in the JSON projection."
  [tickets active-status now-iso]
  (->> tickets
       (filter (fn [t] (= active-status (get-in t [:frontmatter :status]))))
       (sort-by (fn [t] (or (get-in t [:frontmatter :updated]) ""))
                #(compare %2 %1))
       (mapv (fn [t] (assoc t :prime-stale? (stale-in-progress? t now-iso))))))

(defn- count-archive
  "Count tickets whose status is in `terminal-statuses`."
  [tickets terminal-statuses]
  (count (filter (fn [t] (contains? (or terminal-statuses #{})
                                    (get-in t [:frontmatter :status])))
                 tickets)))

(defn- prime-cap
  "Resolve the ready-section cap. Positive integers win; any non-positive
   or non-integer value silently falls back to `prime-default-limit` so
   `knot prime` honours its always-exit-0 contract even on garbage input."
  [limit]
  (if (and (integer? limit) (pos? limit)) limit prime-default-limit))

(defn prime-cmd
  "Render the agent context primer for the project. Returns a string for
   the markdown text or JSON payload. Pair with `main/prime-handler`,
   which wraps the call in a try/catch to honour the always-exit-0
   contract for the global Claude Code `SessionStart` hook — `prime-cmd`
   itself can still propagate exceptions from `store/load-all` on a
   corrupted ticket file.

   `ctx` carries the usual project context plus `:project-found?` (set
   when the walk-up discovery hit a `.knot.edn`/`.tickets/` marker) and
   an optional `:project-name`.
   `opts` supports `:mode` (scalar string — filters all sections), `:limit`
   (override the default ready cap of 20; non-positive values fall back
   to the default), `:json?` (emit the actionable bare-object payload
   instead of markdown), and the standard filter set `:status`,
   `:assignee`, `:tag`, `:type` (each a set of strings from
   `filter-opts-from-cli`).

   Filters apply uniformly across all three sections (in_progress, ready,
   recently_closed). For ready, filters apply BEFORE the cap, so
   `--mode afk --limit 5` yields up to 5 afk-mode ready tickets. The
   recently_closed section is filtered before the compact projection so
   the full ticket fields are available for matching."
  [ctx {:keys [json? mode limit status assignee tag type]}]
  (if-not (:project-found? ctx)
    (let [data {:project          {:found? false}
                :in-progress      []
                :ready            []
                :ready-truncated? false
                :ready-remaining  0
                :active-status    (:active-status (config/defaults))
                :afk-mode         (:afk-mode (config/defaults))}]
      (if json?
        (output/prime-json data)
        (output/prime-text data)))
    (let [{:keys [project-root tickets-dir terminal-statuses active-status
                  prefix project-name now afk-mode]} (resolve-ctx ctx)
          all          (store/load-all project-root tickets-dir)
          archive-cnt  (count-archive all terminal-statuses)
          live-cnt     (- (count all) archive-cnt)
          ;; Unified criteria map — mode is a scalar here (backward compat
          ;; with existing callers), converted to a set for filter-tickets.
          criteria     (cond-> {}
                         (some? mode)     (assoc :mode     #{mode})
                         (seq status)     (assoc :status   status)
                         (seq assignee)   (assoc :assignee assignee)
                         (seq tag)        (assoc :tag      tag)
                         (seq type)       (assoc :type     type))
          in-progress* (query/filter-tickets
                        (in-progress-tickets all active-status now)
                        criteria)
          ready*       (query/ready all terminal-statuses)
          ready-filtered (query/filter-tickets ready* criteria)
          cap          (prime-cap limit)
          ready-shown  (vec (take cap ready-filtered))
          ready-total  (count ready-filtered)
          truncated?   (> ready-total cap)
          remaining    (if truncated? (- ready-total cap) 0)
          recently-closed* (recently-closed-tickets
                            (query/filter-tickets all criteria)
                            terminal-statuses)
          ;; resolve-ctx merges defaults first, so :afk-mode is always
          ;; populated (default "afk" or the user's override, including a
          ;; nil opt-out). Thread it explicitly so the renderer doesn't
          ;; have to fall back to (config/defaults) on the project path.
          data         {:project          {:found?        true
                                           :prefix        prefix
                                           :name          project-name
                                           :live-count    live-cnt
                                           :archive-count archive-cnt}
                        :in-progress      in-progress*
                        :ready            ready-shown
                        :ready-truncated? truncated?
                        :ready-remaining  remaining
                        :recently-closed  recently-closed*
                        :mode             mode
                        :active-status    active-status
                        :afk-mode         afk-mode}]
      (if json?
        (output/prime-json data)
        (output/prime-text data)))))

(defn- effective-create-assignee
  "Mirror `resolve-ctx`'s assignee precedence: an explicit `:default-assignee`
   key in `ctx` (even with a nil value) wins; otherwise fall back to git
   `user.name`. Pure-ish — pulls git through `git/user-name`, which other
   tests redef with `with-redefs`."
  [ctx]
  (if (contains? ctx :default-assignee)
    (:default-assignee ctx)
    (git/user-name)))

(defn- count-md-files
  "Count top-level `*.md` files directly in `dir`. Does not recurse and
   does not parse — a malformed ticket still counts. Returns 0 if `dir`
   is missing."
  [dir]
  (if-not (fs/directory? dir)
    0
    (count (filter #(and (fs/regular-file? %)
                         (str/ends-with? (str (fs/file-name %)) ".md"))
                   (fs/list-dir dir)))))

(defn- info-data
  "Build the snake_case data map used by both `output/info-text` and
   `output/info-json`. Five fixed sections: project, paths, defaults,
   allowed_values, counts."
  [{:keys [project-root prefix project-name config-present? cwd tickets-dir
           default-type default-priority default-mode
           statuses terminal-statuses active-status types modes afk-mode]
    :as ctx}]
  {:project {:knot_version   version/version
             :name           project-name
             :prefix         prefix
             :config_present (boolean config-present?)}
   :paths {:cwd          cwd
           :project_root project-root
           :config_path  (str (fs/path project-root ".knot.edn"))
           :tickets_dir  tickets-dir
           :tickets_path (str (fs/path project-root tickets-dir))
           :archive_path (str (fs/path project-root tickets-dir store/archive-subdir))}
   :defaults {:default_assignee          (when (contains? ctx :default-assignee)
                                           (:default-assignee ctx))
              :effective_create_assignee (effective-create-assignee ctx)
              :default_type              default-type
              :default_priority          default-priority
              :default_mode              default-mode}
   :allowed_values {:statuses          (vec statuses)
                    :active_status     active-status
                    ;; Normalize the terminal-statuses set to an ordered
                    ;; array by filtering :statuses in display order.
                    :terminal_statuses (vec (filter (set terminal-statuses) statuses))
                    :types             (vec types)
                    :modes             (vec modes)
                    :afk_mode          afk-mode
                    :priority_range    {:min 0 :max 4}}
   :counts (let [tickets-path (fs/path project-root tickets-dir)
                 archive-path (fs/path tickets-path store/archive-subdir)
                 live    (count-md-files tickets-path)
                 archive (count-md-files archive-path)]
             {:live_count    live
              :archive_count archive
              :total_count   (+ live archive)})})

(defn info-cmd
  "Report the project's effective runtime configuration and allowed values.
   Returns a string. Unlike `prime-cmd`, the caller (handler) must have
   already discovered and validated the project — `info-cmd` does no
   discovery and never degrades. `opts` supports `:json?`."
  [ctx {:keys [json?]}]
  (let [data (info-data ctx)]
    (if json?
      (output/info-json data)
      (output/info-text data))))

(defn migrate-ac-cmd
  "One-shot v0.3 migration: walk every ticket file (live + archive),
   lift the body's `## Acceptance Criteria` section into a frontmatter
   `:acceptance` list, strip the body section. Tickets with nothing
   to migrate are left untouched on disk so re-running is idempotent
   and `:updated` is preserved for unchanged files. Returns
   `{:migrated <n> :unchanged <n> :total <n>}`."
  [ctx _opts]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)
        scan-result (check/scan project-root tickets-dir)
        tickets     (:tickets scan-result)
        results     (vec (for [t tickets
                               :let [migrated (acceptance/migrate-ticket t)
                                     changed? (not= t migrated)]]
                           {:ticket   t
                            :migrated migrated
                            :changed? changed?}))]
    (doseq [{:keys [migrated changed?]} results
            :when changed?]
      (let [full-id (get-in migrated [:frontmatter :id])]
        (store/save! project-root tickets-dir full-id nil migrated
                     {:now now :terminal-statuses terminal-statuses})))
    {:total     (count results)
     :migrated  (count (filter :changed? results))
     :unchanged (count (remove :changed? results))}))

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
     " ;; Optional human-readable project name shown in `knot prime`.\n"
     " ;; :project-name \"my project\"\n"
     "\n"
     " ;; Default assignee on `knot create`. When this key is set here,\n"
     " ;; it wins over git config user.name. Set to nil to opt out of\n"
     " ;; auto-assignment entirely (no assignee unless --assignee is passed).\n"
     " ;; :default-assignee \"alice\"\n"
     "\n"
     " ;; Default ticket type on `knot create` (must be in :types below).\n"
     " :default-type \"" (:default-type d) "\"\n"
     "\n"
     " ;; Default priority on `knot create` — integer 0..4 (0 = highest).\n"
     " :default-priority " (:default-priority d) "\n"
     "\n"
     " ;; Status workflow, in display order. Edit to add e.g. \"review\".\n"
     " ;; List-table colors are role-driven: terminal statuses render dim,\n"
     " ;; the active lane renders yellow, and the first entry below that is\n"
     " ;; neither active nor terminal renders cyan as the intake lane; any\n"
     " ;; remaining entries render uncolored.\n"
     " :statuses " (pr-str (:statuses d)) "\n"
     "\n"
     " ;; Statuses that are terminal — files of tickets in these statuses\n"
     " ;; auto-move to <tickets-dir>/archive/.\n"
     " :terminal-statuses " (pr-str (:terminal-statuses d)) "\n"
     "\n"
     " ;; The active lane: status `knot start` transitions to, and the one\n"
     " ;; the prime ## In Progress section filters by. Must be a member of\n"
     " ;; :statuses and not in :terminal-statuses. Update this when you\n"
     " ;; edit :statuses to a list that does not include \"in_progress\".\n"
     " :active-status \"" (:active-status d) "\"\n"
     "\n"
     " ;; Allowed values for the :type field on each ticket.\n"
     " :types " (pr-str (:types d)) "\n"
     "\n"
     " ;; Allowed values for the :mode field — `afk` is agent-runnable,\n"
     " ;; `hitl` requires a human. Set the mode on `knot create` with\n"
     " ;; --mode <value> always; do not add per-mode shortcut flags\n"
     " ;; (--<mode-name>) — they bake the values of `:modes` into CLI\n"
     " ;; parsing and break projects that customize this list.\n"
     " :modes " (pr-str (:modes d)) "\n"
     "\n"
     " ;; Default mode on `knot create` (must be in :modes above).\n"
     " :default-mode \"" (:default-mode d) "\"\n"
     "\n"
     " ;; Names which entry in :modes denotes the autonomous-agent role.\n"
     " ;; Drives the `knot prime` agent preamble: when --mode matches this\n"
     " ;; value, prime emits the autonomous-flow directive instead of the\n"
     " ;; human-oriented intent table. Set to nil to disable the agent\n"
     " ;; preamble entirely. Must be a member of :modes (or nil).\n"
     " :afk-mode \"" (:afk-mode d) "\"\n"
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
