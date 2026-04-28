(ns knot.main
  "Entry point. Routes via babashka.cli/parse-args, sets exit codes.
   stdout = data; stderr = warnings/errors."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [knot.cli :as cli]
            [knot.config :as config]
            [knot.output :as output]
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

(def ^:private show-spec
  {:spec
   {:json     {:coerce :boolean}
    :no-color {:coerce :boolean}}})

(def ^:private ls-spec
  {:spec
   {:json     {:coerce :boolean}
    :no-color {:coerce :boolean}}})

(defn- println-out
  "Print a string to stdout. Adds a trailing newline only when `s` does
   not already end in one — keeps `>/dev/null` clean and ensures the
   terminal moves to a fresh line for interactive use."
  [s]
  (if (str/ends-with? s "\n")
    (print s)
    (println s))
  (flush))

(defn- show-handler [argv]
  (let [{:keys [opts args]} (bcli/parse-args argv show-spec)
        id (first args)]
    (when (or (nil? id) (str/blank? id))
      (die "knot show: an id is required"))
    (let [out (cli/show-cmd (discover-ctx)
                            {:id id :json? (boolean (:json opts))})]
      (if out
        (println-out out)
        (die (str "knot show: no ticket matching " id))))))

(defn- ls-handler [argv]
  (let [{:keys [opts]} (bcli/parse-args argv ls-spec)
        json?    (boolean (:json opts))
        tty?     (output/tty?)
        color?   (output/color-enabled?
                  {:tty?         tty?
                   :no-color?    (boolean (:no-color opts))
                   :no-color-env (System/getenv "NO_COLOR")})
        ls-opts  {:json?  json?
                  :tty?   tty?
                  :color? color?}
        out      (cli/ls-cmd (discover-ctx) ls-opts)]
    (println-out out)))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: knot <command> [args...]")
    (println)
    (println "Commands:")
    (println "  create <title> [flags]    Create a new ticket")
    (println "  show   <id>   [--json]    Render the ticket with the given id")
    (println "  ls            [--json]    List live (non-terminal) tickets")))

(defn -main [& argv]
  (try
    (let [[cmd & rest-argv] argv]
      (case cmd
        "create" (create-handler rest-argv)
        "show"   (show-handler rest-argv)
        "ls"     (ls-handler rest-argv)
        nil      (do (usage) (System/exit 1))
        (do (binding [*out* *err*]
              (println (str "knot: unknown command: " cmd)))
            (usage)
            (System/exit 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "knot: " (or (.getMessage e) (.toString e)))))
      (System/exit 1))))
