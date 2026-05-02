(ns knot.help-test
  (:require [babashka.fs]
            [babashka.process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.help :as help]
            [knot.version :as version]))

(def ^:private dep-entry
  "Fixture used across renderer tests."
  {:group       :graph
   :description "Add <to> to <from>'s :deps (cycle-checked)."
   :args        [{:name "from" :required true}
                 {:name "to"   :required true}]
   :flags       []
   :examples    [{:cmd "knot dep kno-abc kno-def"
                  :note "Make kno-abc depend on kno-def."}]
   :exit-codes  [{:code 0 :when "edge saved"}
                 {:code 1 :when "cycle detected or unknown id"}]})

(def ^:private create-entry
  "Fixture for renderer tests covering flags, including a body flag."
  {:group       :lifecycle
   :description "Create a new ticket."
   :args        [{:name "title" :required true}]
   :flags       [{:name :priority :alias :p :coerce :long :desc "Priority 0-4."}
                 {:name :assignee :alias :a :desc "Assignee handle."}
                 {:name :description :alias :d :body? true :desc "Body section."}]
   :examples    [{:cmd "knot create \"Fix login bug\" -p 1"
                  :note "Create a ticket at priority 1."}]
   :exit-codes  [{:code 0 :when "ticket written"}
                 {:code 1 :when "missing title or write failure"}]})

(deftest start-description-active-status-test
  (testing ":start description names the active-lane concept (not the literal in_progress)"
    (let [desc (get-in help/registry [:start :description])]
      (is (string? desc))
      (is (re-find #"(?i)active" desc)
          ":start description references the active-lane concept")
      (is (str/includes? desc "in_progress")
          "the literal default value is named once as a hint, but only as the default"))))

(deftest derive-spec-test
  (testing "a boolean flag becomes a babashka.cli :spec entry with :coerce :boolean"
    (is (= {:spec {:json {:coerce :boolean}}}
           (help/derive-spec {:flags [{:name :json :coerce :boolean}]}))))

  (testing ":alias and :coerce :long are preserved on the derived flag"
    (is (= {:spec {:priority {:coerce :long :alias :p}}}
           (help/derive-spec {:flags [{:name :priority :coerce :long :alias :p
                                       :desc "Priority 0-4."}]}))))

  (testing ":desc is dropped from the derived spec (display-only)"
    (is (= {:spec {:json {:coerce :boolean}}}
           (help/derive-spec {:flags [{:name :json :coerce :boolean
                                       :desc "Emit JSON."}]}))))

  (testing "body flags (:body? true) are excluded from the derived spec"
    (is (= {:spec {:title {}}}
           (help/derive-spec {:flags [{:name :title}
                                      {:name :description :alias :d :body? true}
                                      {:name :design :body? true}
                                      {:name :acceptance :body? true}]}))))

  (testing "no flags yields an empty spec map"
    (is (= {:spec {}}
           (help/derive-spec {:flags []}))))

  (testing ":restrict? true on the entry sets :restrict true on the spec"
    (is (= {:spec {} :restrict true}
           (help/derive-spec {:flags [] :restrict? true}))))

  (testing ":restrict? false (or absent) does not add :restrict to the spec"
    (is (= {:spec {}}
           (help/derive-spec {:flags []})))
    (is (= {:spec {}}
           (help/derive-spec {:flags [] :restrict? false})))))

(deftest synopsis-test
  (testing "no args, no flags"
    (is (= "knot init"
           (help/synopsis "init" {:args [] :flags []}))))

  (testing "required positionals render as <name>"
    (is (= "knot dep <from> <to>"
           (help/synopsis "dep" {:args [{:name "from" :required true}
                                        {:name "to"   :required true}]
                                 :flags []}))))

  (testing "non-required positionals render as [<name>]"
    (is (= "knot add-note <id> [<text>]"
           (help/synopsis "add-note" {:args [{:name "id"   :required true}
                                             {:name "text"}]
                                      :flags []}))))

  (testing "variadic positional renders as [<name>...]"
    (is (= "knot link <a> <b> [<rest>...]"
           (help/synopsis "link" {:args [{:name "a"    :required true}
                                         {:name "b"    :required true}
                                         {:name "rest" :variadic true}]
                                  :flags []}))))

  (testing "any flag presence appends [flags]"
    (is (= "knot ls [flags]"
           (help/synopsis "ls" {:args [] :flags [{:name :json :coerce :boolean}]}))))

  (testing "two-token command name (subcommand) is preserved"
    (is (= "knot dep tree <id> [flags]"
           (help/synopsis "dep tree" {:args  [{:name "id" :required true}]
                                      :flags [{:name :json :coerce :boolean}]})))))

(deftest command-help-text-usage-test
  (testing "renders a USAGE header followed by the indented synopsis"
    (let [out (help/command-help-text "dep" dep-entry {:color? false})]
      (is (str/includes? out "USAGE"))
      (is (str/includes? out "  knot dep <from> <to>"))
      (is (< (str/index-of out "USAGE")
             (str/index-of out "knot dep <from> <to>"))))))

(deftest command-help-text-description-test
  (testing "description renders below the synopsis, separated by a blank line"
    (let [out (help/command-help-text "dep" dep-entry {:color? false})]
      (is (str/includes? out "Add <to> to <from>'s :deps (cycle-checked)."))
      (is (< (str/index-of out "knot dep <from> <to>")
             (str/index-of out "Add <to> to <from>"))))))

(deftest command-help-text-flags-test
  (testing "FLAGS header is omitted when there are no flags"
    (is (not (str/includes?
              (help/command-help-text "dep" dep-entry {:color? false})
              "FLAGS"))))

  (testing "FLAGS section lists each flag with --name, alias, and description"
    (let [out (help/command-help-text "create" create-entry {:color? false})]
      (is (str/includes? out "FLAGS"))
      (is (str/includes? out "--priority"))
      (is (str/includes? out "-p"))
      (is (str/includes? out "Priority 0-4."))
      (is (str/includes? out "--assignee"))
      (is (str/includes? out "-a"))
      (is (str/includes? out "Assignee handle."))))

  (testing "body flags appear in the FLAGS section like any other flag"
    (let [out (help/command-help-text "create" create-entry {:color? false})]
      (is (str/includes? out "--description"))
      (is (str/includes? out "Body section.")))))

(deftest command-help-text-examples-test
  (testing "EXAMPLES section renders one example with the command and the note"
    (let [out (help/command-help-text "dep" dep-entry {:color? false})]
      (is (str/includes? out "EXAMPLES"))
      (is (str/includes? out "knot dep kno-abc kno-def"))
      (is (str/includes? out "Make kno-abc depend on kno-def."))
      (is (< (str/index-of out "EXAMPLES")
             (str/index-of out "knot dep kno-abc")))))

  (testing "EXAMPLES section is omitted when no examples are present"
    (let [stripped (assoc dep-entry :examples [])
          out      (help/command-help-text "dep" stripped {:color? false})]
      (is (not (str/includes? out "EXAMPLES"))))))

(deftest command-help-text-exit-codes-test
  (testing "explicit :exit-codes render under EXIT CODES with code and reason"
    (let [out (help/command-help-text "dep" dep-entry {:color? false})]
      (is (str/includes? out "EXIT CODES"))
      (is (str/includes? out "0"))
      (is (str/includes? out "edge saved"))
      (is (str/includes? out "1"))
      (is (str/includes? out "cycle detected or unknown id"))))

  (testing "absent :exit-codes falls back to the default 0/1 convention"
    (let [stripped (dissoc dep-entry :exit-codes)
          out      (help/command-help-text "dep" stripped {:color? false})]
      (is (str/includes? out "EXIT CODES"))
      (is (str/includes? out "on success"))
      (is (str/includes? out "on error")))))

(def ^:private mini-registry
  "Three-entry registry for subcommand-rendering tests. The `:dep/foo`
   key is a synthetic fixture — it does NOT correspond to any real
   subcommand. Picked deliberately to avoid false-positive grep hits
   for retired commands."
  {:dep      (assoc dep-entry :subcommands [:dep/tree :dep/foo])
   :dep/tree {:group :graph :description "Render the deps subtree."
              :args [{:name "id" :required true}] :flags []}
   :dep/foo  {:group :graph :description "Synthetic fixture subcommand."
              :args [] :flags []}})

(deftest command-help-text-subcommands-test
  (testing "SUBCOMMANDS section lists each subcommand's name + brief"
    (let [out (help/command-help-text "dep" (:dep mini-registry)
                                      {:color? false :registry mini-registry})]
      (is (str/includes? out "SUBCOMMANDS"))
      (is (str/includes? out "knot dep tree"))
      (is (str/includes? out "Render the deps subtree."))
      (is (str/includes? out "knot dep foo"))
      (is (str/includes? out "Synthetic fixture subcommand."))))

  (testing "SUBCOMMANDS section is omitted when entry has no :subcommands"
    (is (not (str/includes?
              (help/command-help-text "dep" dep-entry
                                      {:color? false :registry mini-registry})
              "SUBCOMMANDS")))))

(deftest command-help-text-aliases-test
  (testing "ALIASES section lists each alias when :aliases is present"
    (let [entry (assoc create-entry :aliases ["mk" "new"])
          out   (help/command-help-text "create" entry {:color? false})]
      (is (str/includes? out "ALIASES"))
      (is (str/includes? out "mk"))
      (is (str/includes? out "new"))))

  (testing "ALIASES section is omitted when entry has no :aliases"
    (let [out (help/command-help-text "create" create-entry {:color? false})]
      (is (not (str/includes? out "ALIASES")))))

  (testing "ALIASES section is omitted when :aliases is empty"
    (let [entry (assoc create-entry :aliases [])
          out   (help/command-help-text "create" entry {:color? false})]
      (is (not (str/includes? out "ALIASES"))))))

(deftest resolve-key-test
  (testing "exact registry key resolves to itself"
    (is (= :list (help/resolve-key {:list {:aliases ["ls"]}} "list")))
    (is (= :create (help/resolve-key {:create {}} "create"))))

  (testing "alias string resolves to the underlying registry key"
    (is (= :list (help/resolve-key {:list {:aliases ["ls"]}} "ls"))))

  (testing "unknown name returns nil"
    (is (nil? (help/resolve-key {:list {:aliases ["ls"]}} "bogus"))))

  (testing "an alias matching no entry returns nil"
    (is (nil? (help/resolve-key {:list {}} "ls")))))

(deftest command-help-text-ansi-test
  (testing "with :color? false, no ANSI escapes appear in the output"
    (let [out (help/command-help-text "create" create-entry {:color? false})]
      (is (not (re-find #"\[" out)))))

  (testing "with :color? true, section headers are wrapped in ANSI bold"
    (let [out (help/command-help-text "create" create-entry {:color? true})]
      (is (re-find #"\[1mUSAGE\[0m" out))
      (is (re-find #"\[1mFLAGS\[0m" out))
      (is (re-find #"\[1mEXAMPLES\[0m" out))
      (is (re-find #"\[1mEXIT CODES\[0m" out)))))

(def ^:private toplevel-registry
  {:init      {:group :project   :description "Init a project."}
   :prime     {:group :project   :description "Emit a primer."}
   :create    {:group :lifecycle :description "Create a ticket."
               :args [{:name "title" :required true}]}
   :start     {:group :lifecycle :description "Start a ticket."}
   :dep       {:group :graph     :description "Add a dep edge."
               :args [{:name "from" :required true} {:name "to" :required true}]
               :subcommands [:dep/tree :dep/foo]}
   :dep/tree  {:group :graph     :description "Render deps subtree."
               :args [{:name "id" :required true}]}
   :dep/foo   {:group :graph     :description "Synthetic fixture subcommand."}
   :undep     {:group :graph     :description "Remove a dep edge."}
   :ls        {:group :listing   :description "List live tickets."}
   :show      {:group :listing   :description "Show one ticket."
               :args [{:name "id" :required true}]}
   :add-note  {:group :notes     :description "Append a note."}
   :edit      {:group :notes     :description "Open in $EDITOR."}})

(deftest top-level-help-text-test
  (testing "renders a USAGE header and a top-line synopsis"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (str/includes? out "USAGE"))
      (is (str/includes? out "knot <command>"))))

  (testing "renders the five group headers in canonical order"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})
          idx #(str/index-of out %)]
      (is (idx "Project"))
      (is (idx "Lifecycle"))
      (is (idx "Graph"))
      (is (idx "Listing"))
      (is (idx "Notes"))
      (is (< (idx "Project") (idx "Lifecycle")))
      (is (< (idx "Lifecycle") (idx "Graph")))
      (is (< (idx "Graph") (idx "Listing")))
      (is (< (idx "Listing") (idx "Notes")))))

  (testing "each command appears under its group with its description"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (str/includes? out "init"))
      (is (str/includes? out "Init a project."))
      (is (str/includes? out "create"))
      (is (str/includes? out "Create a ticket."))
      (is (str/includes? out "Add a dep edge."))))

  (testing "subcommands are indented under their parent"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (re-find #"\n    dep tree" out))
      (is (re-find #"\n    dep foo" out))))

  (testing "subcommand keys do not appear flat at the top-level indent"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (not (re-find #"\n  dep tree" out)))))

  (testing "a hint pointing at per-command help appears in the output"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (str/includes? out "knot help <command>"))))

  (testing "with :color? false, no ANSI escapes appear"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (not (re-find #"" out)))))

  (testing "with :color? true, USAGE and group headers are wrapped in ANSI bold"
    (let [out (help/top-level-help-text toplevel-registry {:color? true})]
      (is (re-find #"\[1mUSAGE\[0m" out))
      (is (re-find #"\[1mProject\[0m" out))
      (is (re-find #"\[1mLifecycle\[0m" out))
      (is (re-find #"\[1mGraph\[0m" out))
      (is (re-find #"\[1mListing\[0m" out))
      (is (re-find #"\[1mNotes\[0m" out)))))

(def ^:private expected-cmd-keys
  "Every command name dispatched from `knot.main/-main`'s top-level case
   plus the `dep` subcommand. The registry must cover all of these."
  #{:init :prime :check
    :create :show :list
    :status :start :close :reopen
    :dep :dep/tree :undep
    :link :unlink
    :ready :blocked :closed
    :add-note :edit})

(deftest registry-parity-test
  (testing "every dispatched command has a registry entry"
    (doseq [k expected-cmd-keys]
      (is (contains? help/registry k) (str "missing registry entry: " k))))

  (testing ":list is the official listing command (not :ls)"
    (is (contains? help/registry :list))
    (is (not (contains? help/registry :ls))))

  (testing ":list entry declares ls as an alias"
    (is (= ["ls"] (:aliases (get help/registry :list))))))

(deftest registry-shape-test
  (testing "every entry has a :group from the canonical five"
    (doseq [[k entry] help/registry]
      (is (contains? #{:project :lifecycle :graph :listing :notes}
                     (:group entry))
          (str k " has invalid :group " (:group entry)))))

  (testing "every entry has a non-empty :description"
    (doseq [[k entry] help/registry]
      (is (and (string? (:description entry))
               (seq (:description entry)))
          (str k " missing :description"))))

  (testing "every entry has at least one example"
    (doseq [[k entry] help/registry]
      (is (seq (:examples entry))
          (str k " missing :examples"))))

  (testing "flag aliases are unique within a single command"
    (doseq [[k entry] help/registry
            :let [aliases (->> (:flags entry) (keep :alias))]]
      (is (= (count aliases) (count (set aliases)))
          (str k " has duplicate flag aliases: " aliases)))))

(deftest command-order-parity-test
  (testing "every key in command-order has a registry entry"
    (doseq [k help/command-order]
      (is (contains? help/registry k)
          (str "command-order references missing registry key: " k))))

  (testing "every parent's :subcommands keys exist in the registry"
    (doseq [[k entry] help/registry
            sk        (:subcommands entry)]
      (is (contains? help/registry sk)
          (str k " references missing subcommand: " sk)))))

(deftest create-has-no-mode-shortcut-flags-test
  ;; --mode <value> is the only path to set mode on `knot create`. Per-mode
  ;; shortcut flags (--afk, --hitl) bake canonical mode names into CLI
  ;; parsing — the same hardcoded-canonical-config-literals pattern fixed
  ;; for :active-status. A project that customizes :modes (drops afk/hitl
  ;; or adds new modes) would have shortcut flags referencing modes it
  ;; does not have.
  ;;
  ;; This test pins the *registry* (the source of truth derive-spec reads
  ;; from). The end-to-end parser-rejection contract is pinned by
  ;; create-mode-shortcut-flags-removed-test in integration_test.clj — keep
  ;; the two in lockstep if the :create flag spec ever moves out of the
  ;; help registry.
  (testing ":create flag list contains :mode but no per-mode shortcut keys"
    (let [flag-names (->> (get-in help/registry [:create :flags])
                          (map :name)
                          set)]
      (is (contains? flag-names :mode)
          ":create still exposes the canonical --mode flag")
      (is (not (contains? flag-names :afk))
          ":create must not expose a --afk shortcut flag")
      (is (not (contains? flag-names :hitl))
          ":create must not expose a --hitl shortcut flag")))

  (testing "no registry entry exposes a per-mode shortcut flag"
    ;; Belt-and-suspenders: keep this clean across every command, not just
    ;; :create. If a future command tries to reintroduce the shortcut, this
    ;; test catches it.
    (doseq [[k entry] help/registry
            {:keys [name]} (:flags entry)]
      (is (not (contains? #{:afk :hitl} name))
          (str k " reintroduces a per-mode shortcut flag: " name)))))

;; ---- Integration tests for help routing (subprocess via babashka.process) ----

(def ^:private project-root
  (or (System/getProperty "user.dir") "."))

(defn- run-knot
  "Run `knot <args...>` as a subprocess. Returns `{:exit n :out s :err s}`.
   stdin is closed (empty) so commands probing it don't block."
  [& args]
  @(babashka.process/process
    (concat ["bb" "-cp" (str (babashka.fs/path project-root "src"))
             "-e"
             (str "(require '[knot.main]) "
                  "(apply (resolve 'knot.main/-main) *command-line-args*)")
             "--"]
            args)
    {:in "" :out :string :err :string}))

(deftest top-level-help-routing-test
  (testing "knot --help prints grouped help to stdout and exits 0"
    (let [{:keys [exit out err]} (run-knot "--help")]
      (is (zero? exit) (str "expected exit 0; err=" err))
      (is (str/includes? out "USAGE"))
      (is (str/includes? out "Project"))
      (is (str/includes? out "Lifecycle"))
      (is (str/includes? out "Graph"))
      (is (str/includes? out "Listing"))
      (is (str/includes? out "Notes"))
      (is (str/blank? err) (str "stderr not empty: " err))))

  (testing "knot -h is equivalent to knot --help"
    (let [{:keys [exit out]} (run-knot "-h")]
      (is (zero? exit))
      (is (str/includes? out "Lifecycle"))))

  (testing "knot help (no args) is equivalent to knot --help"
    (let [{:keys [exit out]} (run-knot "help")]
      (is (zero? exit))
      (is (str/includes? out "Lifecycle")))))

(deftest per-command-help-routing-test
  (testing "knot create --help prints per-command help to stdout, exit 0"
    (let [{:keys [exit out err]} (run-knot "create" "--help")]
      (is (zero? exit) (str "err=" err))
      (is (str/includes? out "USAGE"))
      (is (str/includes? out "knot create"))
      (is (str/includes? out "FLAGS"))
      (is (str/blank? err))))

  (testing "knot dep tree --help prints per-command help (two-token cmd)"
    (let [{:keys [exit out]} (run-knot "dep" "tree" "--help")]
      (is (zero? exit))
      (is (str/includes? out "knot dep tree"))
      (is (str/includes? out "Render the deps subtree"))))

  (testing "knot create foo --help: positional first, --help still wins"
    (let [{:keys [exit out]} (run-knot "create" "foo" "--help")]
      (is (zero? exit))
      (is (str/includes? out "knot create"))))

  (testing "knot create --priority high --help: bad coercion does not block help"
    (let [{:keys [exit out]} (run-knot "create" "--priority" "high" "--help")]
      (is (zero? exit))
      (is (str/includes? out "knot create"))))

  (testing "knot help <cmd> renders per-command help for known cmd"
    (let [{:keys [exit out]} (run-knot "help" "create")]
      (is (zero? exit))
      (is (str/includes? out "knot create"))
      (is (str/includes? out "FLAGS"))))

  (testing "knot help dep tree renders per-command help for the subcommand"
    (let [{:keys [exit out]} (run-knot "help" "dep" "tree")]
      (is (zero? exit))
      (is (str/includes? out "knot dep tree"))))

  (testing "knot --help <cmd> renders per-command help"
    (let [{:keys [exit out]} (run-knot "--help" "create")]
      (is (zero? exit))
      (is (str/includes? out "knot create"))))

  (testing "knot -h create renders per-command help"
    (let [{:keys [exit out]} (run-knot "-h" "create")]
      (is (zero? exit))
      (is (str/includes? out "knot create"))))

  (testing "knot help ls resolves the ls alias to the list page"
    (let [{:keys [exit out err]} (run-knot "help" "ls")]
      (is (zero? exit) (str "err=" err))
      (is (str/includes? out "knot list"))
      (is (str/includes? out "ALIASES"))
      (is (str/includes? out "ls"))))

  (testing "knot ls --help also routes to the list page"
    (let [{:keys [exit out]} (run-knot "ls" "--help")]
      (is (zero? exit))
      (is (str/includes? out "knot list"))
      (is (str/includes? out "ALIASES"))))

  (testing "knot help list renders the list page directly"
    (let [{:keys [exit out]} (run-knot "help" "list")]
      (is (zero? exit))
      (is (str/includes? out "knot list"))
      (is (str/includes? out "FLAGS")))))

(deftest body-flag-help-interaction-test
  ;; help-requested? runs `extract-body-flags` first so that a literal
  ;; `--help` inside a body value (`-d "--help"`, `--design --help`, etc.)
  ;; does not trigger the help router. The assertions below verify the
  ;; non-help fall-through path: with no title, create-handler dies with
  ;; a "title is required" error on stderr — proving help routing did
  ;; NOT fire (which would have printed USAGE to stdout and exited 0).
  (testing "literal --help inside --description value does not trigger help"
    (let [{:keys [exit out err]} (run-knot "create" "--description" "--help text")]
      (is (= 1 exit) (str "expected create-handler fall-through, got exit=" exit))
      (is (str/blank? out) (str "no help should print to stdout: " out))
      (is (str/includes? err "title is required"))))

  (testing "literal --help after the -d alias does not trigger help"
    (let [{:keys [exit out err]} (run-knot "create" "-d" "--help text")]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "title is required"))))

  (testing "--design=--help (= form) does not trigger help"
    (let [{:keys [exit out err]} (run-knot "create" "--design=--help")]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "title is required")))))

(deftest help-help-collapse-test
  (testing "knot help help is treated as bare top-level help (exit 0, USAGE)"
    (let [{:keys [exit out err]} (run-knot "help" "help")]
      (is (zero? exit) (str "expected exit 0; err=" err))
      (is (str/includes? out "USAGE"))
      (is (str/includes? out "Lifecycle"))
      (is (str/blank? err))))

  (testing "knot --help -h collapses leading help tokens to top-level help"
    (let [{:keys [exit out]} (run-knot "--help" "-h")]
      (is (zero? exit))
      (is (str/includes? out "USAGE"))))

  (testing "knot help help create still resolves to per-command help"
    (let [{:keys [exit out]} (run-knot "help" "help" "create")]
      (is (zero? exit))
      (is (str/includes? out "knot create"))
      (is (str/includes? out "FLAGS")))))

(deftest help-error-paths-test
  (testing "knot help bogus → stderr error, exit 1"
    (let [{:keys [exit out err]} (run-knot "help" "bogus")]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "unknown"))
      (is (str/includes? err "bogus"))))

  (testing "knot bogus --help → unknown-command stderr error, exit 1"
    (let [{:keys [exit out err]} (run-knot "bogus" "--help")]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "unknown command"))
      (is (str/includes? err "bogus"))))

  (testing "knot --help bogus → stderr error (unknown help target), exit 1"
    (let [{:keys [exit out err]} (run-knot "--help" "bogus")]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "unknown")))))

(deftest bare-knot-hint-test
  (testing "bare knot prints a one-line hint to stderr and exits 1"
    (let [{:keys [exit out err]} (run-knot)]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "knot --help"))))

  (testing "the bare-knot hint does not dump the full grouped help"
    (let [{:keys [err]} (run-knot)]
      (is (not (str/includes? err "Project")))
      (is (not (str/includes? err "Lifecycle")))
      (is (not (str/includes? err "Graph"))))))

(deftest no-ansi-when-piped-test
  (testing "knot --help via subprocess (piped) emits no ANSI escapes"
    (let [{:keys [out]} (run-knot "--help")]
      (is (not (re-find #"\x1b\[" out)))))

  (testing "knot create --help via subprocess (piped) emits no ANSI escapes"
    (let [{:keys [out]} (run-knot "create" "--help")]
      (is (not (re-find #"\x1b\[" out))))))

(deftest cyan-color-test
  (testing "with :color? true, the synopsis under USAGE is cyan"
    (let [out (help/command-help-text "create" create-entry {:color? true})]
      (is (re-find #"\[36mknot create" out))))

  (testing "with :color? true, FLAGS labels are cyan"
    (let [out (help/command-help-text "create" create-entry {:color? true})]
      (is (re-find #"\[36m--priority, -p\[0m" out))))

  (testing "with :color? true, EXAMPLES commands are cyan"
    (let [out (help/command-help-text "create" create-entry {:color? true})]
      (is (re-find #"\[36mknot create" out))))

  (testing "with :color? true, top-level command labels are cyan"
    (let [out (help/top-level-help-text toplevel-registry {:color? true})]
      (is (re-find #"\[36minit\[0m" out))
      (is (re-find #"\[36mcreate <title>\[0m" out))))

  (testing "with :color? true, indented subcommand labels are cyan"
    (let [out (help/top-level-help-text toplevel-registry {:color? true})]
      (is (re-find #"\[36mdep tree <id>\[0m" out))))

  (testing "with :color? false, none of the above color escapes appear"
    (let [out (help/command-help-text "create" create-entry {:color? false})]
      (is (not (re-find #"" out))))))

(deftest top-level-help-version-header-test
  (testing "top-level help renders a `knot v<version>` header above USAGE"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})
          banner (str "knot v" version/version)]
      (is (str/includes? out banner))
      (is (< (str/index-of out banner) (str/index-of out "USAGE"))
          "version banner must appear before USAGE")))

  (testing "version header reflects the current knot.version/version constant"
    (let [out (help/top-level-help-text toplevel-registry {:color? false})]
      (is (re-find (re-pattern (str "knot v" (java.util.regex.Pattern/quote version/version)))
                   out)))))

(deftest version-flag-routing-test
  (testing "knot --version prints the bare version to stdout and exits 0"
    (let [{:keys [exit out err]} (run-knot "--version")]
      (is (zero? exit) (str "expected exit 0; err=" err))
      (is (= (str version/version "\n") out))
      (is (str/blank? err)))))
