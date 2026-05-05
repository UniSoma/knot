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

(def ^:private create-body-flags
  "Body-section flag tokens for `knot create` (long form, `=` form
   prefix, and short alias) mapped to the opts key that `cli/create-cmd`
   expects. `--body` is intentionally absent — that is `knot update`'s
   whole-body-replace flag and would silently swallow the value here."
  {"--description" :description
   "-d"            :description
   "--design"      :design
   "--acceptance"  :acceptance})

(def ^:private update-body-flags
  "Body flags for `knot update`: the create set plus `--body` for
   whole-body replace."
  (assoc create-body-flags "--body" :body))

(def ^:private body-flag->key
  "Union of every command's body-extraction flags. Used by
   `help-requested?` so a literal `--help` inside any command's body
   string never triggers help (the per-command body-extract maps are
   too narrow for that — help detection is command-agnostic)."
  update-body-flags)

(defn- extract-body-flags
  "Walk argv and pull body-section flag tokens (long form, `=` form
   prefix, and short aliases — see `create-body-flags` /
   `update-body-flags`) out before babashka.cli sees them. Supports
   both the `--flag value` and `--flag=value` shapes; values are
   consumed verbatim so dash-prefixed bodies like `\"- [ ] item\"`
   survive intact. `flag-map` scopes which flags get extracted — pass
   `create-body-flags` from `create-handler`, `update-body-flags` from
   `update-handler`, `body-flag->key` from `help-requested?` (the
   union, since help detection is command-agnostic). Returns
   `{:body-opts {kw value} :argv [...]}` where `:argv` is argv with
   the consumed tokens removed."
  [argv flag-map]
  (loop [in argv, out [], opts {}]
    (if (empty? in)
      {:body-opts opts :argv out}
      (let [[head & tail] in
            eq-idx        (when (and (string? head) (str/starts-with? head "--"))
                            (str/index-of head "="))
            eq-flag       (when eq-idx (subs head 0 eq-idx))]
        (cond
          (and eq-flag (contains? flag-map eq-flag))
          (recur tail out
                 (assoc opts (flag-map eq-flag) (subs head (inc eq-idx))))

          (and (contains? flag-map head) (seq tail))
          (recur (rest tail) out
                 (assoc opts (flag-map head) (first tail)))

          :else
          (recur tail (conj out head) opts))))))

(defn- split-tags
  "Split a `--tags` value on commas, trimming whitespace and dropping empties."
  [s]
  (when (string? s)
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- println-out
  "Print a string to stdout. Adds a trailing newline only when `s` does
   not already end in one — keeps `>/dev/null` clean and ensures the
   terminal moves to a fresh line for interactive use."
  [s]
  (if (str/ends-with? s "\n")
    (print s)
    (println s))
  (flush))

(defn- emit-not-found-envelope!
  "Print a v0.3 not_found error envelope to stdout and exit 1."
  [id]
  (println-out (output/error-envelope-str
                {:code    "not_found"
                 :message (str "no ticket matching " id)}))
  (System/exit 1))

(defn- emit-ambiguous-envelope!
  "Print a v0.3 ambiguous_id error envelope to stdout and exit 1.
   `data` is the `ex-data` map carrying `:input` and `:candidates`.
   `:candidates` is already a vector by construction in `knot.store/ambiguous!`."
  [^Exception e data]
  (println-out (output/error-envelope-str
                {:code       "ambiguous_id"
                 :message    (.getMessage e)
                 :candidates (:candidates data)}))
  (System/exit 1))

(defn- emit-error-envelope!
  "Print a v0.3 error envelope to stdout and exit 1. `error` is the map
   passed straight to `output/error-envelope-str` (must include `:code`
   and `:message`; extra keys pass through unchanged)."
  [error]
  (println-out (output/error-envelope-str error))
  (System/exit 1))

(defn- create-handler [argv]
  (let [{:keys [body-opts argv]} (extract-body-flags argv create-body-flags)
        {:keys [opts args]}      (bcli/parse-args argv (spec :create))
        title (first args)
        json? (boolean (:json opts))]
    (when (or (nil? title) (str/blank? title))
      (die "knot create: a title is required"))
    (let [opts (cond-> (-> opts
                           (merge body-opts)
                           (assoc :title title)
                           (assoc :json? json?))
                 (:tags opts) (assoc :tags (split-tags (:tags opts))))
          out  (cli/create-cmd (discover-ctx) opts)]
      (println-out (str out)))))

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
   the listing commands expect for `query/filter-tickets`. Used by every
   listing handler (`ls`, `ready`, `closed`, `blocked`, and — modulo
   `:mode`, which `prime-cmd` keeps as a scalar — `prime`)."
  [opts]
  (reduce (fn [acc k]
            (if-let [s (->set (get opts k))]
              (assoc acc k s)
              acc))
          {}
          [:status :assignee :tag :type :mode]))

(defn- show-handler [argv]
  (let [{:keys [opts args]} (bcli/parse-args argv (spec :show))
        id (first args)
        json? (boolean (:json opts))]
    (when (or (nil? id) (str/blank? id))
      (die "knot show: an id is required"))
    (try
      (let [out (cli/show-cmd (discover-ctx) {:id id :json? json?})]
        (cond
          out   (println-out out)
          json? (emit-not-found-envelope! id)
          :else (die (str "knot show: no ticket matching " id))))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (and json? (= :ambiguous (:kind data)))
            (emit-ambiguous-envelope! e data)
            (throw e)))))))

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
                   (:limit opts) (assoc :limit (:limit opts))
                   tty?          (assoc :width (output/terminal-width)))
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
   used in error messages. The handler prints the new path on stdout
   (or a JSON envelope under `--json`). `--summary <text>` is threaded
   through; the cli layer rejects it on transitions to non-terminal
   statuses. Under `--json`, not-found and ambiguous-id failures route
   to the v0.3 error envelope; arg-parsing errors stay on stderr."
  [cmd-name cmd-key arg-count transition-fn argv]
  (let [{:keys [args opts]} (bcli/parse-args argv (spec cmd-key))
        json? (boolean (:json opts))]
    (when (< (count args) arg-count)
      (die (str "knot " cmd-name ": "
                (case arg-count
                  1 "an id is required"
                  2 "an id and new status are required"))))
    (let [id    (first args)
          base  (if (= arg-count 2)
                  {:id id :status (second args)}
                  {:id id})
          opts* (cond-> (assoc base :json? json?)
                  (contains? opts :summary) (assoc :summary (:summary opts)))]
      (try
        (let [out (transition-fn (discover-ctx) opts*)]
          (cond
            out   (println-out (str out))
            json? (emit-not-found-envelope! id)
            :else (die (str "knot " cmd-name ": no ticket matching " id))))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (cond
              (and json? (= :ambiguous (:kind data)))
              (emit-ambiguous-envelope! e data)

              json?
              (emit-error-envelope! {:code    "invalid_argument"
                                     :message (.getMessage e)})

              :else (throw e))))))))

(defn- edge-handler
  "Run `dep` or `undep`: `knot <cmd> <from> <to>`. On cycle rejection
   (only possible for `dep`), prints the offending path to stderr and
   exits 1 — or emits a `cycle` error envelope under `--json`."
  [cmd-name cmd-key edge-fn argv]
  (let [{:keys [args opts]} (bcli/parse-args argv (spec cmd-key))
        json?               (boolean (:json opts))]
    (when (< (count args) 2)
      (die (str "knot " cmd-name ": <from> and <to> ids are required")))
    (let [[from to] args]
      (try
        (let [out (edge-fn (discover-ctx) {:from from :to to :json? json?})]
          (cond
            out   (println-out (str out))
            json? (emit-not-found-envelope! from)
            :else (die (str "knot " cmd-name ": no ticket matching " from))))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (cond
              (and json? (= :ambiguous (:kind data)))
              (emit-ambiguous-envelope! e data)

              (and json? (:cycle data))
              (emit-error-envelope! {:code    "cycle"
                                     :message (.getMessage e)
                                     :cycle   (:cycle data)})

              (:cycle data)
              (die (str "knot " cmd-name ": " (.getMessage e)))

              :else (throw e))))))))

(defn- dep-tree-handler [argv]
  (let [{:keys [opts args]} (bcli/parse-args argv (spec :dep/tree))
        id    (first args)
        json? (boolean (:json opts))]
    (when (or (nil? id) (str/blank? id))
      (die "knot dep tree: an id is required"))
    (try
      (let [out (cli/dep-tree-cmd (discover-ctx)
                                  {:id    id
                                   :json? json?
                                   :full? (boolean (:full opts))})]
        (println-out out))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (and json? (= :ambiguous (:kind data)))
            (emit-ambiguous-envelope! e data)
            (throw e)))))))

(defn- dep-handler
  "Route `knot dep ...`. `dep tree <id>` is nested; otherwise it's the
   edge form `dep <from> <to>`."
  [argv]
  (case (first argv)
    "tree" (dep-tree-handler (rest argv))
    (edge-handler "dep" :dep cli/dep-cmd argv)))

(defn- list-handler
  "Run a non-mutating list command that shares the `ls`-like output
   shape: `--json`, `--no-color`, `--limit`, and the unified six-flag
   filter set (`--status --assignee --tag --type --mode`). `cmd-key`
   selects the per-command `spec` from the help registry — every
   listing spec carries the full filter set, so the difference is
   purely the upstream source of tickets each `list-fn` walks. Filters
   that survive parsing apply BEFORE `--limit` truncation."
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
   own stdout line, or a single JSON envelope under `--json`. Note the
   intentional asymmetry: the non-json path keeps the existing stderr
   `die` message for ambiguous/not-found, while `--json` upgrades those
   to the structured error envelope on stdout."
  [argv]
  (let [{:keys [args opts]} (bcli/parse-args argv (spec :link))
        json?               (boolean (:json opts))]
    (when (< (count args) 2)
      (die "knot link: two or more ticket ids are required"))
    (try
      (let [out (cli/link-cmd (discover-ctx) {:ids (vec args) :json? json?})]
        (if json?
          (println-out (str out))
          (doseq [path out]
            (println-out (str path)))))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (and json? (= :ambiguous (:kind data)))
            (emit-ambiguous-envelope! e data)

            (and json? (= :not-found (:kind data)))
            (emit-error-envelope! {:code "not_found" :message (.getMessage e)})

            json?
            (emit-error-envelope! {:code "invalid_argument" :message (.getMessage e)})

            :else (die (str "knot link: " (or (.getMessage e) (.toString e)))))))
      (catch Exception e
        (die (str "knot link: " (or (.getMessage e) (.toString e))))))))

(defn- unlink-handler
  "Run `knot unlink <from> <to>`: removes the symmetric link between the
   two ids. Prints each saved path on its own stdout line, or a single
   JSON envelope under `--json`. Same asymmetry as `link-handler`:
   `--json` upgrades ambiguous/not-found to structured envelopes; the
   non-json path keeps the existing stderr `die` message."
  [argv]
  (let [{:keys [args opts]} (bcli/parse-args argv (spec :unlink))
        json?               (boolean (:json opts))]
    (when (< (count args) 2)
      (die "knot unlink: <from> and <to> ids are required"))
    (try
      (let [out (cli/unlink-cmd (discover-ctx)
                                {:from (first args) :to (second args)
                                 :json? json?})]
        (if json?
          (println-out (str out))
          (doseq [path out]
            (println-out (str path)))))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (and json? (= :ambiguous (:kind data)))
            (emit-ambiguous-envelope! e data)

            (and json? (= :not-found (:kind data)))
            (emit-error-envelope! {:code "not_found" :message (.getMessage e)})

            json?
            (emit-error-envelope! {:code "invalid_argument" :message (.getMessage e)})

            :else (die (str "knot unlink: " (or (.getMessage e) (.toString e)))))))
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
   else stdin if not a TTY; else editor. With `--json`, emits a v0.3
   envelope for the post-mutation ticket on success or an error
   envelope (`not_found`, `ambiguous_id`) on resolver failure."
  [argv]
  (let [{:keys [args opts]} (bcli/parse-args argv (spec :add-note))
        json? (boolean (:json opts))
        id    (first args)
        text  (when (>= (count args) 2)
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
          tty? (some? (System/console))]
      (try
        (let [out (cli/add-note-cmd
                   ctx
                   {:id              id
                    :text            text
                    :json?           json?
                    :stdin-tty?      tty?
                    :stdin-reader-fn (fn [] (slurp *in*))
                    :editor-fn       (editor-fn-for-note)})]
          (if out
            (println-out (str out))
            ;; nil from add-note-cmd is either "id missing" or "empty
            ;; content cancelled". Disambiguate by running the same
            ;; resolver. Partial ids must round-trip here too so an
            ;; empty-content cancel on `knot add-note 01abc` doesn't get
            ;; misread as a missing id.
            (if (try
                  (store/resolve-id (:project-root ctx) (:tickets-dir ctx) id)
                  true
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :not-found (:kind (ex-data e)))
                      false
                      (throw e))))
              ;; resolver succeeds → empty content cancellation; exit 0
              ;; quietly (no JSON envelope — the contract documents this).
              (System/exit 0)
              (if json?
                (emit-not-found-envelope! id)
                (die (str "knot add-note: no ticket matching " id))))))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (cond
              (and json? (= :ambiguous (:kind data)))
              (emit-ambiguous-envelope! e data)

              :else (throw e))))))))

(defn- update-handler
  "Handle `knot update <id> [flags...]`. Frontmatter flags route through
   the parser; body flags (`--description / --design / --acceptance /
   --body`) are pre-extracted before babashka.cli sees them so dash-
   prefixed values survive intact. With `--json`, not-found and
   ambiguous-id failures route to the v0.3 error envelope on stdout;
   conflicting body flags emit `invalid_argument`. Tag splitting mirrors
   `create-handler` so the on-disk `:tags` field stays a YAML list."
  [argv]
  (let [{:keys [body-opts argv]} (extract-body-flags argv update-body-flags)
        {:keys [opts args]}      (bcli/parse-args argv (spec :update))
        json? (boolean (:json opts))
        id    (first args)]
    (when (or (nil? id) (str/blank? id))
      (die "knot update: an id is required"))
    (let [opts* (cond-> (-> opts
                            (merge body-opts)
                            (dissoc :json)
                            (assoc :id id :json? json?))
                  (contains? opts :tags)
                  (assoc :tags (split-tags (:tags opts)))

                  ;; `--external-ref ""` from the CLI yields [""] from
                  ;; babashka.cli's `:coerce []`. (empty? [""]) is
                  ;; false, so the cli-layer dissoc-on-empty branch
                  ;; would never fire and the user would end up with a
                  ;; literal blank ref instead of a cleared field.
                  ;; Normalize blanks to drop them (mirrors split-tags).
                  (contains? opts :external-ref)
                  (assoc :external-ref
                         (vec (remove str/blank? (:external-ref opts)))))]
      (try
        (let [out (cli/update-cmd (discover-ctx) opts*)]
          (cond
            out   (println-out (str out))
            json? (emit-not-found-envelope! id)
            :else (die (str "knot update: no ticket matching " id))))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (cond
              (and json? (= :ambiguous (:kind data)))
              (emit-ambiguous-envelope! e data)

              json?
              (emit-error-envelope! {:code    "invalid_argument"
                                     :message (.getMessage e)})

              (= :ambiguous (:kind data)) (throw e)

              :else (die (str "knot update: " (.getMessage e))))))))))

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

(defn- emit-check-result!
  "Apply a `cli/check-cmd` result map to stdout/stderr and exit with its
   `:exit` code. `:stdout`/`:stderr` may each be nil to skip that channel."
  [{:keys [exit stdout stderr]}]
  (when (some? stdout) (println-out stdout))
  (when (some? stderr)
    (binding [*out* *err*] (println stderr)))
  (System/exit exit))

(defn- check-cannot-scan!
  "Emit the cannot-scan exit-2 envelope (json) or stderr message and exit 2.
   Used when project discovery fails or `.knot.edn` is invalid."
  [json? code message]
  (if json?
    (do (println-out (output/error-envelope-str
                      {:code code :message message}))
        (System/exit 2))
    (do (binding [*out* *err*]
          (println (str "knot check: " message)))
        (System/exit 2))))

(defn- check-handler
  "Run `knot check`. Discovers the project (exit 2 on cannot-scan) and
   delegates to `cli/check-cmd`, which returns `{:exit :stdout :stderr}`.
   Argument-parse errors land on stderr with exit 2 (per the
   arg-parsing-stays-on-stderr policy from the JSON-envelope ticket)."
  [argv]
  (let [parsed (try
                 (bcli/parse-args argv (spec :check))
                 (catch Exception e e))]
    (if (instance? Exception parsed)
      (check-cannot-scan! false "invalid_argument" (.getMessage ^Exception parsed))
      (let [{:keys [opts args]} parsed
            json?      (boolean (:json opts))
            cwd        (str (fs/cwd))
            discovered (try (config/discover cwd)
                            (catch Exception e e))]
        (cond
          (instance? Exception discovered)
          (check-cannot-scan! json? "config_invalid"
                              (.getMessage ^Exception discovered))

          (nil? discovered)
          (check-cannot-scan! json? "no_project"
                              (str "no project found at or above " cwd))

          :else
          (let [project-root (:project-root discovered)
                cfg          (:config discovered)
                ctx          (merge cfg
                                    {:project-root   project-root
                                     :prefix         (or (:prefix cfg)
                                                         (ticket/derive-prefix
                                                          (str (fs/file-name project-root))))
                                     :project-found? true})]
            (emit-check-result!
             (cli/check-cmd ctx
                            {:json?    json?
                             :severity (:severity opts)
                             :code     (:code opts)
                             :ids      (vec args)}))))))))

(defn- info-emit-error!
  "Emit the cannot-discover error path for `knot info`. Plain stderr +
   exit 1 by default; with `--json`, a v0.3 error envelope on stdout
   (so JSON consumers can parse it) + exit 1. Reuses the `no_project`
   and `config_invalid` codes from `check`'s exit-2 contract — `info`
   stays on the ordinary 0/1 path because it's a runtime-facts
   command, not a health verdict."
  [json? code message]
  (if json?
    (do (println-out (output/error-envelope-str
                      {:code code :message message}))
        (System/exit 1))
    (do (binding [*out* *err*]
          (println (str "knot info: " message)))
        (System/exit 1))))

(defn- info-handler
  "Run `knot info`. Discovery is strict (no degrade): a missing project
   or invalid `.knot.edn` exits 1 with `no_project` / `config_invalid`
   error envelopes (under `--json`) or stderr (plain). On success, builds
   the same kind of ctx `discover-ctx` produces but adds `:cwd` and
   `:config-present?` so `cli/info-cmd` can report runtime-vs-config
   distinctions."
  [argv]
  (let [parsed (try
                 (bcli/parse-args argv (spec :info))
                 (catch Exception e e))]
    (if (instance? Exception parsed)
      ;; Parse-args failed, so `(:json opts)` is not available — sniff
      ;; `--json` directly from argv to keep the v0.3 envelope contract.
      (info-emit-error! (boolean (some #{"--json"} argv))
                        "invalid_argument" (.getMessage ^Exception parsed))
      (let [{:keys [opts]} parsed
            json?      (boolean (:json opts))
            cwd        (str (fs/cwd))
            discovered (try (config/discover cwd)
                            (catch Exception e e))]
        (cond
          (instance? Exception discovered)
          (info-emit-error! json? "config_invalid"
                            (.getMessage ^Exception discovered))

          (nil? discovered)
          (info-emit-error! json? "no_project"
                            (str "no project found at or above " cwd))

          :else
          (let [project-root (:project-root discovered)
                cfg          (:config discovered)
                cfg-present? (fs/exists? (fs/path project-root ".knot.edn"))
                ctx          (merge cfg
                                    {:project-root     project-root
                                     :cwd              cwd
                                     :prefix           (or (:prefix cfg)
                                                           (ticket/derive-prefix
                                                            (str (fs/file-name project-root))))
                                     :config-present?  cfg-present?
                                     :project-found?   true})]
            (println-out (cli/info-cmd ctx {:json? json?}))))))))

(defn- prime-handler
  "Run `knot prime`. Always exits 0, including in directories with no
   Knot project — the renderer emits a fallback preamble pointing at
   `knot init`. Argument-parsing errors fall back to a minimal primer
   so the command stays safe to wire into a global SessionStart hook."
  [argv]
  (let [out (try
              (let [{:keys [opts]} (bcli/parse-args argv (spec :prime))
                    filter-opts  (dissoc (filter-opts-from-cli opts) :mode)]
                (cli/prime-cmd (discover-ctx)
                               (merge filter-opts
                                      {:json? (boolean (:json opts))
                                       :mode  (:mode opts)
                                       :limit (:limit opts)})))
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
  (boolean (some #{"--help" "-h"} (:argv (extract-body-flags argv body-flag->key)))))

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
        "info"   (info-handler rest-argv)
        "check"  (check-handler rest-argv)
        "create" (create-handler rest-argv)
        "show"   (show-handler rest-argv)
        ("list" "ls") (ls-handler rest-argv)
        "status"  (transition-handler "status" :status 2 cli/status-cmd rest-argv)
        "start"   (transition-handler "start"  :start  1 cli/start-cmd  rest-argv)
        "close"   (transition-handler "close"  :close  1 cli/close-cmd  rest-argv)
        "reopen"  (transition-handler "reopen" :reopen 1 cli/reopen-cmd rest-argv)
        "dep"      (dep-handler rest-argv)
        "undep"    (edge-handler "undep" :undep cli/undep-cmd rest-argv)
        "link"     (link-handler   rest-argv)
        "unlink"   (unlink-handler rest-argv)
        "ready"    (list-handler :ready   cli/ready-cmd   rest-argv)
        "blocked"  (list-handler :blocked cli/blocked-cmd rest-argv)
        "closed"   (list-handler :closed  cli/closed-cmd  rest-argv)
        "add-note" (add-note-handler rest-argv)
        "edit"     (edit-handler rest-argv)
        "update"   (update-handler rest-argv)
        nil      (do (usage) (System/exit 1))
        (do (binding [*out* *err*]
              (println (str "knot: unknown command: " cmd)))
            (usage)
            (System/exit 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "knot: " (or (.getMessage e) (.toString e)))))
      (System/exit 1))))
