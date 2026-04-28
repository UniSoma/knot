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
  "Build a command context: walk up from cwd to find the project root, load
   `.knot.edn` (when present), and derive the prefix from config (when set)
   or from the project directory name. Falls back to cwd + defaults when no
   project marker is found anywhere up the tree."
  []
  (let [cwd       (str (fs/cwd))
        discovered (config/discover cwd)
        root      (or (:project-root discovered) cwd)
        cfg       (or (:config discovered) (config/defaults))
        prefix    (or (:prefix cfg)
                      (ticket/derive-prefix (str (fs/file-name root))))]
    (merge cfg
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

(def ^:private init-spec
  {:spec
   {:prefix      {}
    :tickets-dir {}
    :force       {:coerce :boolean}}})

(defn- init-handler [argv]
  (let [{:keys [opts]} (bcli/parse-args argv init-spec)
        ;; init runs in cwd by design — it's how you create a project root
        ctx  {:project-root (str (fs/cwd))}
        path (cli/init-cmd ctx opts)]
    (println-out (str path))))

(defn- transition-handler
  "Run a single-id status-mutation command (`status`/`start`/`close`/`reopen`)
   via `transition-fn`. `arg-count` is the number of positional args
   consumed (1 for start/close/reopen, 2 for status). `cmd-name` is
   used in error messages. The handler prints the new path on stdout."
  [cmd-name arg-count transition-fn argv]
  (let [{:keys [args]} (bcli/parse-args argv {:spec {}})]
    (when (< (count args) arg-count)
      (die (str "knot " cmd-name ": "
                (case arg-count
                  1 "an id is required"
                  2 "an id and new status are required"))))
    (let [id   (first args)
          opts (if (= arg-count 2)
                 {:id id :status (second args)}
                 {:id id})
          path (transition-fn (discover-ctx) opts)]
      (if path
        (println-out (str path))
        (die (str "knot " cmd-name ": no ticket matching " id))))))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: knot <command> [args...]")
    (println)
    (println "Commands:")
    (println "  init                      Write .knot.edn stub and create the tickets dir")
    (println "  create <title> [flags]    Create a new ticket")
    (println "  show   <id>   [--json]    Render the ticket with the given id")
    (println "  ls            [--json]    List live (non-terminal) tickets")
    (println "  status <id> <new-status>  Transition a ticket to a new status")
    (println "  start  <id>               Transition a ticket to in_progress")
    (println "  close  <id>               Transition a ticket to the first terminal status")
    (println "  reopen <id>               Transition a ticket back to open")))

(defn -main [& argv]
  (try
    (let [[cmd & rest-argv] argv]
      (case cmd
        "init"   (init-handler rest-argv)
        "create" (create-handler rest-argv)
        "show"   (show-handler rest-argv)
        "ls"     (ls-handler rest-argv)
        "status" (transition-handler "status" 2 cli/status-cmd rest-argv)
        "start"  (transition-handler "start"  1 cli/start-cmd  rest-argv)
        "close"  (transition-handler "close"  1 cli/close-cmd  rest-argv)
        "reopen" (transition-handler "reopen" 1 cli/reopen-cmd rest-argv)
        nil      (do (usage) (System/exit 1))
        (do (binding [*out* *err*]
              (println (str "knot: unknown command: " cmd)))
            (usage)
            (System/exit 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "knot: " (or (.getMessage e) (.toString e)))))
      (System/exit 1))))
