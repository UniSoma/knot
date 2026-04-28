(ns knot.main
  "Entry point. Routes via babashka.cli/parse-args, sets exit codes.
   stdout = data; stderr = warnings/errors."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [knot.cli :as cli]
            [knot.config :as config]
            [knot.ticket :as ticket]))

(defn- discover-ctx
  "Build a command context: walk up from cwd to find the project root, fall
   back to cwd, and derive the prefix from the project directory name."
  []
  (let [cwd      (str (fs/cwd))
        defaults (config/defaults)
        root     (or (config/find-project-root cwd (:tickets-dir defaults))
                     cwd)
        prefix   (ticket/derive-prefix (str (fs/file-name root)))]
    (merge defaults
           {:project-root root
            :prefix       prefix})))

(defn- die
  "Print `msg` to stderr and exit 1."
  [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(def ^:private create-spec
  {:spec
   {:description  {:alias :d}
    :design       {}
    :acceptance   {}
    :type         {:alias :t}
    :priority     {:alias :p :coerce :long}
    :assignee     {:alias :a}
    :external-ref {:coerce []}
    :parent       {}
    :tags         {}
    :mode         {}}})

(defn- split-tags
  "Split a `--tags` value on commas, trimming whitespace and dropping empties."
  [s]
  (when (string? s)
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- create-handler [argv]
  (let [{:keys [opts args]} (bcli/parse-args argv create-spec)
        title (first args)]
    (when (or (nil? title) (str/blank? title))
      (die "knot create: a title is required"))
    (let [opts (cond-> (assoc opts :title title)
                 (:tags opts) (assoc :tags (split-tags (:tags opts))))
          path (cli/create-cmd (discover-ctx) opts)]
      (println (str path)))))

(defn- show-handler [argv]
  (let [id (first argv)]
    (when (or (nil? id) (str/blank? id))
      (die "knot show: an id is required"))
    (let [out (cli/show-cmd (discover-ctx) {:id id})]
      (if out
        (do (print out) (flush))
        (die (str "knot show: no ticket matching " id))))))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: knot <command> [args...]")
    (println)
    (println "Commands:")
    (println "  create <title> [flags]   Create a new ticket")
    (println "  show   <id>              Render the ticket with the given id")))

(defn -main [& argv]
  (try
    (let [[cmd & rest-argv] argv]
      (case cmd
        "create" (create-handler rest-argv)
        "show"   (show-handler rest-argv)
        nil      (do (usage) (System/exit 1))
        (do (binding [*out* *err*]
              (println (str "knot: unknown command: " cmd)))
            (usage)
            (System/exit 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "knot: " (or (.getMessage e) (.toString e)))))
      (System/exit 1))))
