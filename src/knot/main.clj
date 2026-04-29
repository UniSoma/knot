(ns knot.main
  "Entry point. Routes via babashka.cli/parse-args, sets exit codes.
   stdout = data; stderr = warnings/errors."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [knot.cli :as cli]
            [knot.config :as config]
            [knot.help :as help]
            [knot.output :as output]
            [knot.store :as store]
            [knot.ticket :as ticket]
            [knot.version :as version]))

(defn- spec
  "Look up a registry entry by id and derive its babashka.cli :spec map.
   This is the single bridge between the help registry (source of truth
   for flags) and the parser."
  [cmd-key]
  (help/derive-spec (get help/registry cmd-key)))

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

;; Body-section flags (`--description / --design / --acceptance`, plus the
;; `-d` alias) are pre-extracted from argv by `extract-body-flags` before
;; delegating to `babashka.cli`. They live in `knot.help/registry` with
;; `:body? true`, which causes `derive-spec` to skip them — so they never
;; appear in the parser spec, only in help output. The reason for the
;; pre-extraction: bb-cli splits a value on whitespace before binding, so
;; a value like `"- [ ] item"` makes it interpret `-`/`[`/`]`/`item` as
;; flags and either crashes coercion (`--priority`) or writes garbage
;; into other spec entries.

(def ^:private body-flag->key
  "Body-section flag tokens (long form, `=` form prefix, and short alias)
   mapped to the opts key that `cli/create-cmd` expects."
  {"--description" :description
   "-d"            :description
   "--design"      :design
   "--acceptance"  :acceptance})

(defn- extract-body-flags
  "Walk argv and pull `--description / --design / --acceptance` (plus the
   `-d` alias) out before babashka.cli sees them. Supports both the
   `--flag value` and `--flag=value` shapes; values are consumed verbatim
   so dash-prefixed bodies like `\"- [ ] item\"` survive intact. Returns
   `{:body-opts {kw value} :argv [...]}` where `:argv` is argv with the
   consumed tokens removed."
  [argv]
  (loop [in argv, out [], opts {}]
    (if (empty? in)
      {:body-opts opts :argv out}
      (let [[head & tail] in
            eq-idx        (when (and (string? head) (str/starts-with? head "--"))
                            (str/index-of head "="))
            eq-flag       (when eq-idx (subs head 0 eq-idx))]
        (cond
          (and eq-flag (contains? body-flag->key eq-flag))
          (recur tail out
                 (assoc opts (body-flag->key eq-flag) (subs head (inc eq-idx))))

          (and (contains? body-flag->key head) (seq tail))
          (recur (rest tail) out
                 (assoc opts (body-flag->key head) (first tail)))

          :else
          (recur tail (conj out head) opts))))))

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
  (let [{:keys [body-opts argv]} (extract-body-flags argv)
        {:keys [opts args]}      (bcli/parse-args argv (spec :create))
        title (first args)]
    (when (or (nil? title) (str/blank? title))
      (die "knot create: a title is required"))
    (let [resolved-mode (resolve-mode opts)
          opts          (cond-> (-> opts
                                    (merge body-opts)
                                    (dissoc :afk :hitl)
                                    (assoc :title title))
                          (:tags opts)        (assoc :tags (split-tags (:tags opts)))
                          (some? resolved-mode) (assoc :mode resolved-mode))
          path          (cli/create-cmd (discover-ctx) opts)]
      (println (str path)))))


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
  (let [{:keys [opts args]} (bcli/parse-args argv (spec :show))
        id (first args)]
    (when (or (nil? id) (str/blank? id))
      (die "knot show: an id is required"))
    (let [out (cli/show-cmd (discover-ctx)
                            {:id id :json? (boolean (:json opts))})]
      (if out
        (println-out out)
        (die (str "knot show: no ticket matching " id))))))

(defn- ls-handler [argv]
  (let [{:keys [opts]} (bcli/parse-args argv (spec :list))
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

(defn- init-handler [argv]
  (let [{:keys [opts]} (bcli/parse-args argv (spec :init))
        ;; init runs in cwd by design — it's how you create a project root
        ctx  {:project-root (str (fs/cwd))}
        path (cli/init-cmd ctx opts)]
    (println-out (str path))))

(defn- transition-handler
  "Run a single-id status-mutation command (`status`/`start`/`close`/`reopen`)
   via `transition-fn`. `arg-count` is the number of positional args
   consumed (1 for start/close/reopen, 2 for status). `cmd-name` is
   used in error messages. The handler prints the new path on stdout.
   `--summary <text>` is threaded through; the cli layer rejects it on
   transitions to non-terminal statuses."
  [cmd-name cmd-key arg-count transition-fn argv]
  (let [{:keys [args opts]} (bcli/parse-args argv (spec cmd-key))]
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

(defn- dep-tree-handler [argv]
  (let [{:keys [opts args]} (bcli/parse-args argv (spec :dep/tree))
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

(defn- list-handler
  "Run a non-mutating list command that shares the `ls`-like output
   shape: `--json`, `--no-color`, optionally `--limit`, optionally
   filter flags. `spec` controls which flags are accepted — `ready`
   passes `ready-spec` (filters); `closed`/`blocked` pass `list-spec`
   (no filters). Filters that survive parsing apply BEFORE `--limit`
   truncation."
  [cmd-key list-fn argv]
  (let [{:keys [opts]} (bcli/parse-args argv (spec cmd-key))
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
        ;; cancelled". Disambiguate by running the same resolver. Partial
        ;; ids must round-trip here too so an empty-content cancel on
        ;; `knot add-note 01abc` doesn't get misread as a missing id.
        (if (try
              (store/resolve-id (:project-root ctx) (:tickets-dir ctx) id)
              true
              (catch clojure.lang.ExceptionInfo e
                (if (= :not-found (:kind (ex-data e)))
                  false
                  (throw e))))
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

(defn- prime-handler
  "Run `knot prime`. Always exits 0, including in directories with no
   Knot project — the renderer emits a fallback preamble pointing at
   `knot init`. Argument-parsing errors fall back to a minimal primer
   so the command stays safe to wire into a global SessionStart hook."
  [argv]
  (let [out (try
              (let [{:keys [opts]} (bcli/parse-args argv (spec :prime))]
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

(defn- usage
  "One-line hint pointing the caller at `knot --help`. Used for bare
   `knot` invocation and as the trailing line on the unknown-command
   error path. The full grouped help lives behind `--help`."
  []
  (binding [*out* *err*]
    (println "Usage: knot <command> [args...]. Run `knot --help` for the full list.")))

(defn- color-enabled-on-stdout? []
  (output/color-enabled? {:tty?         (output/tty?)
                          :no-color-env (System/getenv "NO_COLOR")}))

(defn- print-top-level-help []
  (println-out (help/top-level-help-text help/registry
                                         {:color? (color-enabled-on-stdout?)})))

(defn- resolve-cmd-key
  "Look at the first one or two non-flag tokens of `argv` and return the
   matching registry key, preferring the two-token form when both match.
   Returns nil when no token sequence matches. Single-token names are
   resolved through `help/resolve-key` so command aliases (e.g. `ls` →
   `:list`) route to the canonical help entry."
  [argv]
  (let [[a b] argv]
    (cond
      (and a b (contains? help/registry (keyword a b))) (keyword a b)
      (some? a)                                         (help/resolve-key help/registry a))))

(defn- print-command-help [k]
  (println-out (help/command-help-text (help/key->cmd-name k)
                                       (get help/registry k)
                                       {:color?   (color-enabled-on-stdout?)
                                        :registry help/registry})))

(defn- help-requested?
  "True when `argv` (after body-flag extraction) contains `--help` or
   `-h`. Body extraction keeps a literal `--help` inside a body string
   from triggering a false positive."
  [argv]
  (boolean (some #{"--help" "-h"} (:argv (extract-body-flags argv)))))

(defn -main [& argv]
  (try
    (let [[cmd & rest-argv] argv]
      ;; `--version` short-circuits everything else: print bare version
      ;; and exit 0. `-v` is intentionally NOT wired — that slot is
      ;; reserved for a future `--verbose`.
      (when (= "--version" cmd)
        (println version/version)
        (System/exit 0))

      ;; Top-level / per-command help. `knot help`, `knot --help`, `knot -h`
      ;; (alone or chained with other help tokens like `knot help help`,
      ;; `knot --help -h`) all collapse to top-level help. With a trailing
      ;; non-help token, the remainder is resolved as a per-command help
      ;; target. Unknown target → stderr, exit 1.
      (when (#{"--help" "-h" "help"} cmd)
        (let [tail (drop-while #{"help" "--help" "-h"} rest-argv)]
          (cond
            (empty? tail)
            (do (print-top-level-help) (System/exit 0))

            :else
            (if-let [k (resolve-cmd-key tail)]
              (do (print-command-help k) (System/exit 0))
              (die (str "knot help: unknown command: " (str/join " " tail)))))))

      ;; Per-command `--help` / `-h` anywhere in argv: pre-scan after
      ;; extracting body flags so a literal `--help` inside a body value
      ;; does not trigger help. Falls through if `cmd` is unknown so the
      ;; existing case dispatch produces the unknown-command error.
      (when (and cmd (help-requested? rest-argv))
        (when-let [k (resolve-cmd-key (cons cmd rest-argv))]
          (print-command-help k)
          (System/exit 0)))

      (case cmd
        "init"   (init-handler rest-argv)
        "prime"  (prime-handler rest-argv)
        "create" (create-handler rest-argv)
        "show"   (show-handler rest-argv)
        ("list" "ls") (ls-handler rest-argv)
        "status"  (transition-handler "status" :status 2 cli/status-cmd rest-argv)
        "start"   (transition-handler "start"  :start  1 cli/start-cmd  rest-argv)
        "close"   (transition-handler "close"  :close  1 cli/close-cmd  rest-argv)
        "reopen"  (transition-handler "reopen" :reopen 1 cli/reopen-cmd rest-argv)
        "dep"      (dep-handler rest-argv)
        "undep"    (edge-handler "undep" cli/undep-cmd rest-argv)
        "link"     (link-handler   rest-argv)
        "unlink"   (unlink-handler rest-argv)
        "ready"    (list-handler :ready   cli/ready-cmd   rest-argv)
        "blocked"  (list-handler :blocked cli/blocked-cmd rest-argv)
        "closed"   (list-handler :closed  cli/closed-cmd  rest-argv)
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
