(ns knot.config
  "Project root walk-up discovery and `.knot.edn` parsing with defaults."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private default-config
  {:tickets-dir       ".tickets"
   :default-type      "task"
   :default-priority  2
   :statuses          ["open" "in_progress" "closed"]
   :terminal-statuses #{"closed"}
   :active-status     "in_progress"
   :types             ["bug" "feature" "task" "epic" "chore"]
   :modes             ["afk" "hitl"]
   :default-mode      "hitl"
   :afk-mode          "afk"
   :doc-types         ["spec" "plan" "other"]
   :default-doc-type  "other"
   ;; nil means `<tickets-dir>/docs`, so renaming :tickets-dir carries the
   ;; document corpus with it. A string resolves against the project root,
   ;; matching :skill-dir, and lets a project keep this writing wherever it
   ;; already keeps long-form writing.
   :docs-dir          nil
   ;; {<status> [<doc-type> ...]}. A transition INTO a listed status is
   ;; refused until the ticket owns a document of every named type. Empty
   ;; by default: a gate nobody asked for is a gate that fires by surprise.
   :required-docs     {}})

(defn defaults
  "Return the v0 schema defaults. `load-config` merges `.knot.edn`
   overrides on top of these."
  []
  default-config)

(defn- has-marker?
  "True when `dir` contains `.knot.edn` or `<tickets-dir>/`."
  [dir tickets-dir]
  (or (fs/exists? (fs/path dir ".knot.edn"))
      (fs/directory? (fs/path dir tickets-dir))))

(defn find-project-root-within
  "Walk up from `start-dir` searching for `.knot.edn` or `.tickets/`, stopping
   at `boundary` (exclusive). Returns the path string of the first matching
   ancestor, or nil."
  [start-dir boundary]
  (let [boundary-canon (fs/canonicalize boundary)]
    (loop [d (fs/canonicalize start-dir)]
      (cond
        (= (str d) (str boundary-canon)) nil
        (has-marker? d ".tickets")       (str d)
        :else
        (let [parent (fs/parent d)]
          (when (and parent (not= (str parent) (str d)))
            (recur parent)))))))

(defn find-project-root
  "Walk up from `start-dir` searching for `.knot.edn` or `.tickets/`.
   Returns the path string of the first matching ancestor, or nil if none."
  [start-dir]
  (loop [d (fs/canonicalize start-dir)]
    (cond
      (has-marker? d ".tickets") (str d)
      :else
      (let [parent (fs/parent d)]
        (when (and parent (not= (str parent) (str d)))
          (recur parent))))))

(def ^:private known-keys
  #{:tickets-dir :prefix :project-name :default-assignee :default-type
    :default-priority :statuses :terminal-statuses :active-status
    :types :modes :default-mode :afk-mode :skill-dir
    :doc-types :default-doc-type :docs-dir :required-docs})

(defn- warn! [msg]
  (binding [*out* *err*] (println msg)))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn- list-of-non-blank-strings? [v]
  (and (sequential? v)
       (seq v)
       (every? non-blank-string? v)))

(defn active-status-issue
  "Pure predicate: returns nil when `:active-status` is a valid choice
   given `:statuses` and `:terminal-statuses` in the merged config; else
   returns `{:code :invalid_active_status :message <s>}` describing the
   violation. Used by `validate!` (which throws) and by `knot.check`
   (which surfaces it as a global integrity issue)."
  [{:keys [statuses terminal-statuses active-status]}]
  (when-not (and (non-blank-string? active-status)
                 ((set statuses) active-status)
                 (not (contains? terminal-statuses active-status)))
    {:code    :invalid_active_status
     :message (str ".knot.edn :active-status " (pr-str active-status)
                   " must be one of :statuses " (pr-str (vec statuses))
                   " and not in :terminal-statuses "
                   (pr-str terminal-statuses)
                   " — set :active-status explicitly when customizing :statuses")}))

(defn validate!
  "Throw with a clear message when any value in `merged` (defaults + user
   overrides) violates the schema. Returns `merged` unchanged on success."
  [merged]
  (let [{:keys [tickets-dir prefix project-name default-assignee default-type
                default-priority statuses terminal-statuses active-status
                types modes default-mode afk-mode skill-dir
                doc-types default-doc-type docs-dir required-docs]} merged]
    (when-not (non-blank-string? tickets-dir)
      (throw (ex-info ".knot.edn :tickets-dir must be a non-blank string" {})))
    (when (and (some? prefix) (not (and (non-blank-string? prefix)
                                        (re-matches #"[a-z0-9]+" prefix))))
      (throw (ex-info ".knot.edn :prefix must be a non-empty [a-z0-9]+ string" {})))
    (when (and (some? project-name) (not (non-blank-string? project-name)))
      (throw (ex-info ".knot.edn :project-name must be a non-blank string" {})))
    (when (and (some? default-assignee) (not (non-blank-string? default-assignee)))
      (throw (ex-info ".knot.edn :default-assignee must be a non-blank string" {})))
    (when-not (list-of-non-blank-strings? statuses)
      (throw (ex-info ".knot.edn :statuses must be a non-empty list of strings" {})))
    (when-not (and (set? terminal-statuses)
                   (every? non-blank-string? terminal-statuses)
                   (every? (set statuses) terminal-statuses))
      (throw (ex-info (str ".knot.edn :terminal-statuses must be a set of "
                           "strings, all present in :statuses") {})))
    (when-let [issue (active-status-issue merged)]
      (throw (ex-info (:message issue)
                      {:active-status     active-status
                       :statuses          statuses
                       :terminal-statuses terminal-statuses})))
    (when-not (list-of-non-blank-strings? types)
      (throw (ex-info ".knot.edn :types must be a non-empty list of strings" {})))
    (when-not ((set types) default-type)
      (throw (ex-info ".knot.edn :default-type must be one of :types" {})))
    (when-not (list-of-non-blank-strings? modes)
      (throw (ex-info ".knot.edn :modes must be a non-empty list of strings" {})))
    (when-not ((set modes) default-mode)
      (throw (ex-info ".knot.edn :default-mode must be one of :modes" {})))
    (when (and (some? afk-mode)
               (not ((set modes) afk-mode)))
      (throw (ex-info (str ".knot.edn :afk-mode " (pr-str afk-mode)
                           " must be one of :modes " (pr-str (vec modes))
                           " (or nil to disable the agent preamble)")
                      {:afk-mode afk-mode :modes modes})))
    (when-not (and (integer? default-priority) (<= 0 default-priority 4))
      (throw (ex-info ".knot.edn :default-priority must be an integer 0..4" {})))
    (when (and (some? skill-dir) (not (non-blank-string? skill-dir)))
      (throw (ex-info ".knot.edn :skill-dir must be a non-blank string" {})))
    ;; An empty :doc-types is refused, not tolerated: the enum
    ;; validator skips validation entirely on an empty allowed set, so an
    ;; empty list would silently disable the allow-list instead of narrowing
    ;; it. That is a degenerate config value, not a legitimate shrink, so it
    ;; belongs here at load, not in `check`.
    (when-not (list-of-non-blank-strings? doc-types)
      (throw (ex-info ".knot.edn :doc-types must be a non-empty list of strings" {})))
    (when-not ((set doc-types) default-doc-type)
      (throw (ex-info ".knot.edn :default-doc-type must be one of :doc-types" {})))
    (when (and (some? docs-dir) (not (non-blank-string? docs-dir)))
      (throw (ex-info ".knot.edn :docs-dir must be a non-blank string, or nil for <tickets-dir>/docs" {})))
    ;; Validated against BOTH enums it spans, because a typo in either half
    ;; is silent otherwise: an unknown status gates nothing and an unknown
    ;; type gates forever.
    (when-not (map? required-docs)
      (throw (ex-info ".knot.edn :required-docs must be a map of status to doc types" {})))
    (doseq [[status types] required-docs]
      (when-not ((set statuses) status)
        (throw (ex-info (str ".knot.edn :required-docs key " (pr-str status)
                             " must be one of :statuses " (pr-str (vec statuses)))
                        {:status status :statuses statuses})))
      (when-not (list-of-non-blank-strings? types)
        (throw (ex-info (str ".knot.edn :required-docs " (pr-str status)
                             " must be a non-empty list of strings") {})))
      (doseq [t types]
        (when-not ((set doc-types) t)
          (throw (ex-info (str ".knot.edn :required-docs " (pr-str status)
                               " names doc type " (pr-str t)
                               ", which is not in :doc-types " (pr-str (vec doc-types)))
                          {:status status :type t :doc-types doc-types}))))))
  merged)

(defn load-config
  "Read `<root>/.knot.edn` (when present), validate, and merge on top of
   `defaults`. Unknown keys are dropped with a stderr warning. Invalid
   values throw with a clear message. Returns the merged map."
  [root]
  (let [path (fs/path root ".knot.edn")
        defs (defaults)]
    (if-not (fs/exists? path)
      defs
      (let [raw     (try
                      (edn/read-string (slurp (str path)))
                      (catch Exception e
                        (throw (ex-info (str ".knot.edn at " path
                                             " is not valid EDN: "
                                             (.getMessage e))
                                        {:path (str path)} e))))
            unknown (remove known-keys (keys raw))]
        (when (seq unknown)
          (warn! (str "knot: ignoring unknown .knot.edn keys: "
                      (str/join ", " (map name unknown)))))
        (validate! (merge defs (select-keys raw known-keys)))))))

(defn discover
  "Walk up from `start-dir` for the project root and load `.knot.edn`.
   The first ancestor containing either `.knot.edn` or `.tickets/` (the
   default tickets-dir) is the project root. When that ancestor has a
   `.knot.edn`, the config's `:tickets-dir` wins over any on-disk dir
   name. Returns `{:project-root <path> :config <merged>}` or nil when
   no marker is found."
  [start-dir]
  (when-let [root (find-project-root start-dir)]
    {:project-root root
     :config       (load-config root)}))

(defn discover-within
  "Like `discover`, but stops walking at `boundary` (exclusive). Useful for
   tests that need to bound the walk to a temp directory tree."
  [start-dir boundary]
  (when-let [root (find-project-root-within start-dir boundary)]
    {:project-root root
     :config       (load-config root)}))
