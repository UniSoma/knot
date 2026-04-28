(ns knot.config
  "Project root walk-up discovery and `.knot.edn` parsing with defaults."
  (:require [babashka.fs :as fs]))

(def ^:private default-config
  {:tickets-dir       ".tickets"
   :default-type      "task"
   :default-priority  2
   :statuses          ["open" "in_progress" "closed"]
   :terminal-statuses #{"closed"}
   :types             ["bug" "feature" "task" "epic" "chore"]
   :modes             ["afk" "hitl"]
   :default-mode      "hitl"})

(defn defaults
  "Return the v0 schema defaults. `.knot.edn` overrides are not yet wired."
  []
  default-config)

(defn- has-marker?
  "True when `dir` contains `.knot.edn` or `<tickets-dir>/`."
  [dir tickets-dir]
  (or (fs/exists? (fs/path dir ".knot.edn"))
      (fs/directory? (fs/path dir tickets-dir))))

(defn find-project-root-within
  "Walk up from `start-dir` searching for `.knot.edn` or `<tickets-dir>/`,
   stopping at `boundary` (exclusive). Returns the path string of the first
   matching ancestor, or nil. `tickets-dir` defaults to `.tickets`."
  ([start-dir boundary]
   (find-project-root-within start-dir boundary ".tickets"))
  ([start-dir boundary tickets-dir]
   (let [boundary-canon (fs/canonicalize boundary)]
     (loop [d (fs/canonicalize start-dir)]
       (cond
         (= (str d) (str boundary-canon)) nil
         (has-marker? d tickets-dir)      (str d)
         :else
         (let [parent (fs/parent d)]
           (when (and parent (not= (str parent) (str d)))
             (recur parent))))))))

(defn find-project-root
  "Walk up from `start-dir` searching for `.knot.edn` or `<tickets-dir>/`.
   Returns the path string of the first matching ancestor, or nil if none.
   `tickets-dir` defaults to `.tickets`."
  ([start-dir]
   (find-project-root start-dir ".tickets"))
  ([start-dir tickets-dir]
   (loop [d (fs/canonicalize start-dir)]
     (cond
       (has-marker? d tickets-dir) (str d)
       :else
       (let [parent (fs/parent d)]
         (when (and parent (not= (str parent) (str d)))
           (recur parent)))))))
