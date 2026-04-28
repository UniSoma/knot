(ns knot.main
  "Entry point. Routes via babashka.cli/parse-args, sets exit codes.
   stdout = data; stderr = warnings/errors."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [knot.cli :as cli]
            [knot.config :as config]
            [knot.output :as output]
            [knot.store :as store]
            [knot.ticket :as ticket]))

(defn- discover-ctx
  "Build a command context: walk up from cwd to find the project root, load
   `.knot.edn` (when present), and derive the prefix from config (when set)
   or from the project directory name. Falls back to cwd + defaults when no
   project marker is found anywhere up the tree. `:project-found?` records
   whether the walk-up actually hit a marker so commands like `prime` can
   render a fallback preamble without conditional fall-throughs."
  []
  (let [cwd       (str (fs/cwd))
        discovered (config/discover cwd)
        root      (or (:project-root discovered) cwd)
        cfg       (or (:config discovered) (config/defaults))
        prefix    (or (:prefix cfg)
                      (ticket/derive-prefix (str (fs/file-name root))))]
    (merge cfg
           {:project-root   root
            :prefix         prefix
            :project-found? (some? discovered)})))

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
    :mode         {}
    :afk          {:coerce :boolean}
    :hitl         {:coerce :boolean}}})

(defn- resolve-mode
  "Reconcile `--mode`, `--afk`, `--hitl`. Explicit `:mode` wins; otherwise
   `:afk` → \"afk\", `:hitl` → \"hitl\". When both shortcuts are set with
   no explicit `--mode`, throw — the caller's intent is ambiguous."
  [{:keys [mode afk hitl]}]
  (cond
    (some? mode)         mode
    (and afk hitl)
    (throw (ex-info "knot create: --afk and --hitl are mutually exclusive"
                    {:afk afk :hitl hitl}))
    afk                  "afk"
    hitl                 "hitl"
    :else                nil))

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
    (let [resolved-mode (resolve-mode opts)
          opts          (cond-> (-> opts
                                    (dissoc :afk :hitl)
                                    (assoc :title title))
                          (:tags opts)        (assoc :tags (split-tags (:tags opts)))
                          (some? resolved-mode) (assoc :mode resolved-mode))
          path          (cli/create-cmd (discover-ctx) opts)]
      (println (str path)))))

(def ^:private show-spec
  {:spec
   {:json     {:coerce :boolean}
    :no-color {:coerce :boolean}}})

(def ^:private ls-spec
  {:spec
   {:json     {:coerce :boolean}
    :no-color {:coerce :boolean}
    :status   {:coerce []}
    :assignee {:coerce []}
    :tag      {:coerce []}
    :type     {:coerce []}
    :mode     {:coerce []}}})

(defn- ->set
  "Coerce an opt value to a non-empty set, or nil. babashka.cli with
   `:coerce []` always returns a vector even for a single occurrence; a
   bare string is also tolerated. Empty inputs become nil so the cli
   primitive treats the dimension as unfiltered."
  [v]
  (cond
    (nil? v)        nil
    (string? v)     #{v}
    (sequential? v) (when (seq v) (set v))
    :else           nil))

(defn- filter-opts-from-cli
  "Project the parsed CLI `opts` map onto the keyword-set shape that
   `cli/ls-cmd` and `cli/ready-cmd` expect for `query/filter-tickets`."
  [opts]
  (reduce (fn [acc k]
            (if-let [s (->set (get opts k))]
              (assoc acc k s)
              acc))
          {}
          [:status :assignee :tag :type :mode]))

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
        ls-opts  (cond-> (merge (filter-opts-from-cli opts)
                                {:json?  json?
                                 :tty?   tty?
                                 :color? color?})
                   tty? (assoc :width (output/terminal-width)))
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

(def ^:private transition-spec
  {:spec {:summary {}}})

(defn- transition-handler
  "Run a single-id status-mutation command (`status`/`start`/`close`/`reopen`)
   via `transition-fn`. `arg-count` is the number of positional args
   consumed (1 for start/close/reopen, 2 for status). `cmd-name` is
   used in error messages. The handler prints the new path on stdout.
   `--summary <text>` is threaded through; the cli layer rejects it on
   transitions to non-terminal statuses."
  [cmd-name arg-count transition-fn argv]
  (let [{:keys [args opts]} (bcli/parse-args argv transition-spec)]
    (when (< (count args) arg-count)
      (die (str "knot " cmd-name ": "
                (case arg-count
                  1 "an id is required"
                  2 "an id and new status are required"))))
    (let [id    (first args)
          base  (if (= arg-count 2)
                  {:id id :status (second args)}
                  {:id id})
          opts* (cond-> base
                  (contains? opts :summary) (assoc :summary (:summary opts)))
          path  (transition-fn (discover-ctx) opts*)]
      (if path
        (println-out (str path))
        (die (str "knot " cmd-name ": no ticket matching " id))))))

(defn- edge-handler
  "Run `dep` or `undep`: `knot <cmd> <from> <to>`. On cycle rejection
   (only possible for `dep`), prints the offending path to stderr and
   exits 1."
  [cmd-name edge-fn argv]
  (let [{:keys [args]} (bcli/parse-args argv {:spec {}})]
    (when (< (count args) 2)
      (die (str "knot " cmd-name ": <from> and <to> ids are required")))
    (let [[from to] args]
      (try
        (let [path (edge-fn (discover-ctx) {:from from :to to})]
          (if path
            (println-out (str path))
            (die (str "knot " cmd-name ": no ticket matching " from))))
        (catch Exception e
          (if (:cycle (ex-data e))
            (die (str "knot " cmd-name ": " (.getMessage e)))
            (throw e)))))))

(def ^:private dep-tree-spec
  {:spec
   {:json {:coerce :boolean}
    :full {:coerce :boolean}}})

(defn- dep-tree-handler [argv]
  (let [{:keys [opts args]} (bcli/parse-args argv dep-tree-spec)
        id (first args)]
    (when (or (nil? id) (str/blank? id))
      (die "knot dep tree: an id is required"))
    (let [out (cli/dep-tree-cmd (discover-ctx)
                                {:id    id
                                 :json? (boolean (:json opts))
                                 :full? (boolean (:full opts))})]
      (println-out out))))

(defn- dep-cycle-handler [_argv]
  (let [cycles (cli/dep-cycle-cmd (discover-ctx) {})]
    (if (empty? cycles)
      (System/exit 0)
      (do (binding [*out* *err*]
            (doseq [c cycles]
              (println (str "knot dep cycle: " (str/join " → " c)))))
          (System/exit 1)))))

(defn- dep-handler
  "Route `knot dep ...`. `dep tree <id>` and `dep cycle` are nested;
   otherwise it's the edge form `dep <from> <to>`."
  [argv]
  (case (first argv)
    "tree"  (dep-tree-handler (rest argv))
    "cycle" (dep-cycle-handler (rest argv))
    (edge-handler "dep" cli/dep-cmd argv)))

(def ^:private list-spec
  "Spec for `closed`/`blocked` — the narrow shape: output flags and
   `--limit` only. `:restrict true` rejects unknown flags loudly so
   typos and filter flags don't silently no-op."
  {:spec
   {:json     {:coerce :boolean}
    :no-color {:coerce :boolean}
    :limit    {:coerce :long}}
   :restrict true})

(def ^:private ready-spec
  "Spec for `ready` — `list-spec` plus the composable filter flags. The
   filter flags share their `:coerce []` shape with `ls-spec` so a
   value like `--mode afk` parses identically across both commands."
  {:spec
   (merge (:spec list-spec)
          {:status   {:coerce []}
           :assignee {:coerce []}
           :tag      {:coerce []}
           :type     {:coerce []}
           :mode     {:coerce []}})
   :restrict true})

(defn- list-handler
  "Run a non-mutating list command that shares the `ls`-like output
   shape: `--json`, `--no-color`, optionally `--limit`, optionally
   filter flags. `spec` controls which flags are accepted — `ready`
   passes `ready-spec` (filters); `closed`/`blocked` pass `list-spec`
   (no filters). Filters that survive parsing apply BEFORE `--limit`
   truncation."
  [spec list-fn argv]
  (let [{:keys [opts]} (bcli/parse-args argv spec)
        json?    (boolean (:json opts))
        tty?     (output/tty?)
        color?   (output/color-enabled?
                  {:tty?         tty?
                   :no-color?    (boolean (:no-color opts))
                   :no-color-env (System/getenv "NO_COLOR")})
        cmd-opts (cond-> (merge (filter-opts-from-cli opts)
                                {:json?  json?
                                 :tty?   tty?
                                 :color? color?})
                   (:limit opts) (assoc :limit (:limit opts))
                   tty?          (assoc :width (output/terminal-width)))
        out      (list-fn (discover-ctx) cmd-opts)]
    (println-out out)))

(defn- link-handler
  "Run `knot link <a> <b> [<c> ...]`: requires at least two ids; writes
   symmetric `:links` across every pair. Prints each saved path on its
   own stdout line."
  [argv]
  (let [{:keys [args]} (bcli/parse-args argv {:spec {}})]
    (when (< (count args) 2)
      (die "knot link: two or more ticket ids are required"))
    (try
      (doseq [path (cli/link-cmd (discover-ctx) {:ids (vec args)})]
        (println-out (str path)))
      (catch Exception e
        (die (str "knot link: " (or (.getMessage e) (.toString e))))))))

(defn- unlink-handler
  "Run `knot unlink <from> <to>`: removes the symmetric link between the
   two ids. Prints each saved path on its own stdout line."
  [argv]
  (let [{:keys [args]} (bcli/parse-args argv {:spec {}})]
    (when (< (count args) 2)
      (die "knot unlink: <from> and <to> ids are required"))
    (try
      (doseq [path (cli/unlink-cmd (discover-ctx)
                                   {:from (first args) :to (second args)})]
        (println-out (str path)))
      (catch Exception e
        (die (str "knot unlink: " (or (.getMessage e) (.toString e))))))))

(def ^:private editor-header
  "# Lines starting with '#' will be ignored.\n")

(defn- strip-editor-comments
  "Remove lines beginning with `#` (per the editor header convention)."
  [s]
  (->> (str/split-lines (or s ""))
       (remove (fn [line] (str/starts-with? line "#")))
       (str/join "\n")))

(defn- spawn-editor!
  "Run `editor` on `path` with inherited stdio, blocking until the editor
   exits. Throws when the editor process exits non-zero."
  [editor path]
  (let [{:keys [exit]} @(process/process [editor (str path)] {:inherit true})]
    (when-not (zero? exit)
      (throw (ex-info (str "editor exited with status " exit)
                      {:editor editor :exit exit})))))

(defn- editor-fn-for-note
  "Return an editor-fn that opens a temp file pre-filled with the editor
   header and a context line, lets the user edit, then returns the file
   contents with comment lines stripped. The temp file is cleaned up
   regardless of editor exit status."
  []
  (fn [ctx-line]
    (let [editor (cli/resolve-editor (System/getenv))
          tmp    (fs/create-temp-file {:prefix "knot-note-" :suffix ".md"})]
      (try
        (spit (str tmp) (str editor-header "# " ctx-line "\n\n"))
        (spawn-editor! editor (str tmp))
        (strip-editor-comments (slurp (str tmp)))
        (finally (fs/delete-if-exists tmp))))))

(defn- editor-fn-for-edit
  "Return an editor-fn that opens an existing file in the resolved editor."
  []
  (fn [path]
    (let [editor (cli/resolve-editor (System/getenv))]
      (spawn-editor! editor path))))

(defn- add-note-handler
  "Handle `knot add-note <id> [text]`. Layered input: text arg wins;
   else stdin if not a TTY; else editor."
  [argv]
  (let [{:keys [args]} (bcli/parse-args argv {:spec {}})
        id   (first args)
        text (when (>= (count args) 2)
               (str/join " " (rest args)))]
    (when (or (nil? id) (str/blank? id))
      (die "knot add-note: an id is required"))
    ;; (System/console) returns nil when *either* stdin or stdout is
    ;; redirected. That handles the common shapes correctly (piped stdin
    ;; reads stdin; pure-interactive opens the editor). The unusual shape
    ;; `knot add-note id > out.txt` (stdout redirected, stdin still a
    ;; TTY) is treated as non-TTY and slurps stdin — typically empty,
    ;; which cleanly cancels. A precise stdin-isatty probe needs JNI or
    ;; a shell-out and is parked.
    (let [ctx  (discover-ctx)
          tty? (some? (System/console))
          path (cli/add-note-cmd
                 ctx
                 {:id              id
                  :text            text
                  :stdin-tty?      tty?
                  :stdin-reader-fn (fn [] (slurp *in*))
                  :editor-fn       (editor-fn-for-note)})]
      (if path
        (println-out (str path))
        ;; nil from add-note-cmd is either "id missing" or "empty content
        ;; cancelled". Disambiguate by re-checking the id against the
        ;; same ctx we just used.
        (if (store/find-existing-path (:project-root ctx)
                                      (:tickets-dir ctx)
                                      id)
          (System/exit 0)
          (die (str "knot add-note: no ticket matching " id)))))))

(defn- edit-handler
  "Handle `knot edit <id>`."
  [argv]
  (let [{:keys [args]} (bcli/parse-args argv {:spec {}})
        id (first args)]
    (when (or (nil? id) (str/blank? id))
      (die "knot edit: an id is required"))
    (let [path (cli/edit-cmd (discover-ctx)
                             {:id id :editor-fn (editor-fn-for-edit)})]
      (if path
        (println-out (str path))
        (die (str "knot edit: no ticket matching " id))))))

(def ^:private prime-spec
  {:spec
   {:json  {:coerce :boolean}
    :mode  {}
    :limit {:coerce :long}}
   :restrict true})

(defn- prime-handler
  "Run `knot prime`. Always exits 0, including in directories with no
   Knot project — the renderer emits a fallback preamble pointing at
   `knot init`. Argument-parsing errors fall back to a minimal primer
   so the command stays safe to wire into a global SessionStart hook."
  [argv]
  (let [out (try
              (let [{:keys [opts]} (bcli/parse-args argv prime-spec)]
                (cli/prime-cmd (discover-ctx)
                               {:json? (boolean (:json opts))
                                :mode  (:mode opts)
                                :limit (:limit opts)}))
              (catch Exception _
                ;; Argument-parsing or unexpected failure: degrade to a
                ;; no-project primer rather than exit non-zero. SessionStart
                ;; safety is the contract — never break the agent's session.
                (cli/prime-cmd {:project-found? false} {})))]
    (println-out out)))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: knot <command> [args...]")
    (println)
    (println "Commands:")
    (println "  init                       Write .knot.edn stub and create the tickets dir")
    (println "  prime          [--json] [--mode <m>] [--limit N]")
    (println "                             Emit a five-section primer for AI agent context-injection")
    (println "  create <title> [flags]     Create a new ticket")
    (println "  show   <id>    [--json]    Render the ticket with the given id")
    (println "  ls             [--json]    List live (non-terminal) tickets")
    (println "  status <id> <new-status> [--summary <text>]")
    (println "                             Transition a ticket to a new status")
    (println "  start  <id>                Transition a ticket to in_progress")
    (println "  close  <id> [--summary <text>]")
    (println "                             Transition a ticket to the first terminal status")
    (println "  reopen <id>                Transition a ticket back to open")
    (println "  add-note <id> [text]       Append a timestamped note (text arg, stdin, or editor)")
    (println "  edit <id>                  Open the ticket file in $VISUAL/$EDITOR")
    (println "  dep    <from> <to>         Add <to> to <from>'s :deps (cycle-checked)")
    (println "  undep  <from> <to>         Remove <to> from <from>'s :deps")
    (println "  dep tree <id> [--json]     Render the deps subtree (--full to expand dups)")
    (println "  dep cycle                  Scan open tickets for cycles (exit 1 if any)")
    (println "  link   <a> <b> [<c>...]    Create symmetric :links across every pair")
    (println "  unlink <a> <b>             Remove the symmetric link between two ids")
    (println "  ready           [--json] [--mode <m>] [--limit N]")
    (println "                             List tickets whose deps are all closed")
    (println "  blocked         [--json]   List tickets with at least one open dep")
    (println "  closed          [--json] [--limit N]")
    (println "                             List terminal tickets, newest closed first")))

(defn -main [& argv]
  (try
    (let [[cmd & rest-argv] argv]
      (case cmd
        "init"   (init-handler rest-argv)
        "prime"  (prime-handler rest-argv)
        "create" (create-handler rest-argv)
        "show"   (show-handler rest-argv)
        "ls"     (ls-handler rest-argv)
        "status"  (transition-handler "status" 2 cli/status-cmd rest-argv)
        "start"   (transition-handler "start"  1 cli/start-cmd  rest-argv)
        "close"   (transition-handler "close"  1 cli/close-cmd  rest-argv)
        "reopen"  (transition-handler "reopen" 1 cli/reopen-cmd rest-argv)
        "dep"      (dep-handler rest-argv)
        "undep"    (edge-handler "undep" cli/undep-cmd rest-argv)
        "link"     (link-handler   rest-argv)
        "unlink"   (unlink-handler rest-argv)
        "ready"    (list-handler ready-spec cli/ready-cmd   rest-argv)
        "blocked"  (list-handler list-spec  cli/blocked-cmd rest-argv)
        "closed"   (list-handler list-spec  cli/closed-cmd  rest-argv)
        "add-note" (add-note-handler rest-argv)
        "edit"     (edit-handler rest-argv)
        nil      (do (usage) (System/exit 1))
        (do (binding [*out* *err*]
              (println (str "knot: unknown command: " cmd)))
            (usage)
            (System/exit 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "knot: " (or (.getMessage e) (.toString e)))))
      (System/exit 1))))
