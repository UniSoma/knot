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

(defn show-cmd
  "Load the ticket whose id is `(:id opts)` from the project's tickets-dir
   and return its rendered text. With `:json? true`, returns a bare JSON
   object instead. Returns nil when no matching ticket exists."
  [ctx opts]
  (let [{:keys [project-root tickets-dir]} (resolve-ctx ctx)
        loaded (store/load-one project-root tickets-dir (:id opts))]
    (when loaded
      (if (:json? opts)
        (output/show-json loaded)
        (output/show-text loaded)))))

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
   no ticket matches the id."
  [ctx {:keys [id status]}]
  (let [{:keys [project-root tickets-dir terminal-statuses now]}
        (resolve-ctx ctx)]
    (when-let [loaded (store/load-one project-root tickets-dir id)]
      (let [new-fm (assoc (:frontmatter loaded) :status status)
            ticket (assoc loaded :frontmatter new-fm)]
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

(defn ls-cmd
  "List live tickets — those whose status is not in `:terminal-statuses`.
   With `:json? true`, returns a bare JSON array. Otherwise returns the
   rendered text table. Pass `:tty?` and `:color?` to control the table
   format; pass `:width` to constrain TITLE truncation when on a TTY."
  [ctx opts]
  (let [{:keys [project-root tickets-dir terminal-statuses]} (resolve-ctx ctx)
        all     (store/load-all project-root tickets-dir)
        visible (query/non-terminal all terminal-statuses)]
    (if (:json? opts)
      (output/ls-json visible)
      (output/ls-table visible (select-keys opts [:tty? :color? :width])))))

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
