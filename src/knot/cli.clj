(ns knot.cli
  "Per-command argument specs and handlers. Wired by knot.main via
   babashka.cli/dispatch."
  (:require [clojure.string :as str]
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
   caller did not provide them."
  [ctx]
  (let [defaults (config/defaults)]
    (merge defaults
           {:assignee (when-not (contains? ctx :assignee)
                        (git/user-name))}
           ctx
           ;; deterministic 'now' for tests; fall back to wall clock
           (when-not (:now ctx) {:now (now-iso)}))))

(defn create-cmd
  "Create a new ticket from `opts` and write it via `knot.store/save!`.
   Returns the saved path. `ctx` carries `:project-root`, `:prefix`, and
   optional overrides for defaults (`:tickets-dir`, `:default-type`, etc.).
   `opts` is the parsed argument map, e.g. `{:title \"Fix login\" :priority 0}`."
  [ctx opts]
  (let [{:keys [project-root prefix tickets-dir
                default-type default-priority default-mode now assignee]
         :as ctx*} (resolve-ctx ctx)
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
    (store/save! project-root tickets-dir id slug ticket)))

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
