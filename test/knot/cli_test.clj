(ns knot.cli-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.cli :as cli]
            [knot.config :as config]
            [knot.git :as git]
            [knot.store :as store]
            [knot.ticket :as ticket]))

(defmacro with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(defn- ctx [project-root]
  (merge (config/defaults)
         {:project-root project-root
          :prefix       "kno"
          :now          "2026-04-28T10:00:00Z"
          :assignee     nil}))

(deftest create-cmd-test
  (testing "create with a title writes a ticket file under .tickets/"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Fix login bug"})]
        (is (fs/exists? path))
        (is (str/includes? path ".tickets"))
        (is (str/ends-with? path "--fix-login-bug.md")))))

  (testing "the new ticket has the expected frontmatter"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp) {:title "Fix login bug"})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--fix-login-bug\.md")
                        second)
            loaded (store/load-one tmp ".tickets" id)
            fm     (:frontmatter loaded)]
        (is (= id (:id fm)))
        (is (= "open" (:status fm)))
        (is (= "task" (:type fm)))
        (is (= 2 (:priority fm)))
        (is (= "hitl" (:mode fm)))
        (is (= "2026-04-28T10:00:00Z" (:created fm)))
        (is (= "2026-04-28T10:00:00Z" (:updated fm))))))

  (testing "the title is in frontmatter and the body has no empty section placeholders"
    (with-tmp tmp
      (let [path    (cli/create-cmd (ctx tmp) {:title "Fix login bug"})
            content (slurp path)]
        (is (str/includes? content "title: Fix login bug"))
        (is (not (str/includes? content "## Description")))
        (is (not (str/includes? content "## Design")))
        (is (not (str/includes? content "## Acceptance Criteria"))))))

  (testing "supplied --description writes a ## Description section"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "T" :description "Some text."})
            body (slurp path)]
        (is (str/includes? body "## Description"))
        (is (str/includes? body "Some text.")))))

  (testing "supplied --design writes its body section"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp)
                                 {:title       "T"
                                  :design      "Design notes."})
            body (slurp path)]
        (is (str/includes? body "## Design"))
        (is (str/includes? body "Design notes.")))))

  (testing "--acceptance writes the frontmatter list (NOT a body section)"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp)
                                   {:title      "T"
                                    :acceptance ["First criterion"
                                                 "Second criterion"]})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--t\.md")
                        second)
            loaded (store/load-one tmp ".tickets" id)
            fm     (:frontmatter loaded)
            body   (:body loaded)]
        (is (= [{:title "First criterion"  :done false}
                {:title "Second criterion" :done false}]
               (:acceptance fm))
            "frontmatter carries the structured list, each entry done:false")
        (is (not (str/includes? body "## Acceptance Criteria"))
            "the body never carries an Acceptance Criteria section"))))

  (testing "--acceptance synthesizes the section under `knot show`"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp)
                                   {:title      "T"
                                    :acceptance ["Ship it"]})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--t\.md")
                        second)
            shown  (cli/show-cmd (ctx tmp) {:id id})]
        (is (str/includes? shown "## Acceptance Criteria"))
        (is (str/includes? shown "- [ ] Ship it")))))

  (testing "explicit --type/--priority/--assignee/--parent/--tags/--external-ref are stored"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp)
                                   {:title         "Hello"
                                    :type          "bug"
                                    :priority      0
                                    :assignee      "alice"
                                    :parent        "kno-other"
                                    :tags          ["a" "b"]
                                    :external-ref  ["JIRA-1" "JIRA-2"]})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--hello\.md")
                        second)
            loaded (store/load-one tmp ".tickets" id)
            fm     (:frontmatter loaded)]
        (is (= "bug" (:type fm)))
        (is (= 0 (:priority fm)))
        (is (= "alice" (:assignee fm)))
        (is (= "kno-other" (:parent fm)))
        (is (= ["a" "b"] (vec (:tags fm))))
        (is (= ["JIRA-1" "JIRA-2"] (vec (:external_refs fm)))))))

  (testing "empty title produces a bare <id>.md filename"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title ""})]
        (is (re-matches #".+/\.tickets/kno-[0-9a-z]{12}\.md" path)))))

  (testing "rendered frontmatter has stable, human-readable key order (id first, title second)"
    (with-tmp tmp
      (let [path  (cli/create-cmd (ctx tmp) {:title "T"
                                             :assignee "alice"
                                             :tags ["a"]
                                             :parent "kno-other"
                                             :external-ref ["X"]})
            lines (str/split-lines (slurp path))
            ;; first line is the opening fence; second line is the first key
            first-keys (->> lines
                            (drop 1)
                            (take-while #(not= "---" %))
                            (map #(re-find #"^[a-z_]+" %))
                            vec)]
        (is (= "id" (first first-keys)))
        (is (= ["id" "title" "status" "type" "priority" "mode" "created" "updated"]
               (vec (take 8 first-keys))))))))

(deftest create-cmd-json-test
  (testing "create-cmd with :json? returns a v0.3 success-envelope JSON string for the new ticket"
    (with-tmp tmp
      (let [out    (cli/create-cmd (ctx tmp) {:title "Fix login bug" :json? true})
            parsed (cheshire/parse-string out true)]
        (is (= 1 (:schema_version parsed)))
        (is (= true (:ok parsed)))
        (is (string? (get-in parsed [:data :id])))
        (is (str/starts-with? (get-in parsed [:data :id]) "kno-"))
        (is (= "Fix login bug" (get-in parsed [:data :title])))
        (is (= "open" (get-in parsed [:data :status])))
        (is (contains? (:data parsed) :body)
            "single-ticket envelope includes the body")
        (is (not (contains? parsed :meta))
            "create emits no :meta slot")))))

(deftest create-cmd-title-in-frontmatter-test
  (testing "title is written into frontmatter (not just into the body H1)"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp) {:title "Fix login bug"})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--fix-login-bug\.md")
                        second)
            loaded (store/load-one tmp ".tickets" id)]
        (is (= "Fix login bug" (:title (:frontmatter loaded)))))))

  (testing "title appears in frontmatter at position 2 (right after id)"
    (with-tmp tmp
      (let [path  (cli/create-cmd (ctx tmp) {:title "Hello"})
            lines (str/split-lines (slurp path))
            first-keys (->> lines
                            (drop 1)
                            (take-while #(not= "---" %))
                            (map #(re-find #"^[a-z_]+" %))
                            vec)]
        (is (= "id"    (nth first-keys 0)))
        (is (= "title" (nth first-keys 1)))))))

(deftest create-cmd-body-no-h1-test
  (testing "body does NOT contain a synthesized `# <title>` H1 line"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Fix login bug"})
            body (:body (ticket/parse (slurp path)))]
        (is (not (str/includes? body "# Fix login bug"))
            "no H1 in the body — title lives in frontmatter only"))))

  (testing "with no section flags, the body is empty (no synthesized header)"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Solo"})
            body (:body (ticket/parse (slurp path)))]
        (is (= "" body)
            "body is empty when no sections supplied"))))

  (testing "with --description, the body is sections-only (no H1)"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "T"
                                            :description "Desc."})
            body (:body (ticket/parse (slurp path)))]
        (is (not (str/includes? body "# T")))
        (is (str/starts-with? body "## Description"))))))

(deftest create-cmd-default-assignee-from-git-test
  ;; Wiring test for issue 0001 AC: `git config user.name` is the default
  ;; assignee. The other create-cmd tests pass a ctx with `:assignee nil`
  ;; (explicit override), which bypasses the git-defaulting branch in
  ;; resolve-ctx — these tests dissoc :assignee so the branch fires.
  (testing "with :assignee absent from ctx and no opt override, defaults to git/user-name"
    (with-redefs [git/user-name (fn [] "git-configured-user")]
      (with-tmp tmp
        (let [ctx-no-assignee (dissoc (ctx tmp) :assignee)
              path   (cli/create-cmd ctx-no-assignee {:title "Hello"})
              id     (->> (fs/file-name path)
                          (re-matches #"(.+)--hello\.md")
                          second)
              loaded (store/load-one tmp ".tickets" id)]
          (is (= "git-configured-user" (:assignee (:frontmatter loaded))))))))

  (testing "an explicit --assignee opt takes precedence over git/user-name"
    (with-redefs [git/user-name (fn [] "git-configured-user")]
      (with-tmp tmp
        (let [ctx-no-assignee (dissoc (ctx tmp) :assignee)
              path   (cli/create-cmd ctx-no-assignee
                                     {:title "Hello" :assignee "explicit"})
              id     (->> (fs/file-name path)
                          (re-matches #"(.+)--hello\.md")
                          second)
              loaded (store/load-one tmp ".tickets" id)]
          (is (= "explicit" (:assignee (:frontmatter loaded))))))))

  (testing "when git/user-name returns nil, no :assignee key is written"
    (with-redefs [git/user-name (fn [] nil)]
      (with-tmp tmp
        (let [ctx-no-assignee (dissoc (ctx tmp) :assignee)
              path   (cli/create-cmd ctx-no-assignee {:title "Hello"})
              id     (->> (fs/file-name path)
                          (re-matches #"(.+)--hello\.md")
                          second)
              loaded (store/load-one tmp ".tickets" id)]
          (is (not (contains? (:frontmatter loaded) :assignee))))))))

(deftest create-cmd-default-assignee-config-authoritative-test
  ;; `:default-assignee` is authoritative: when the key is present in ctx
  ;; (loaded from `.knot.edn`), it wins over `git config user.name`. This
  ;; lets users set a project-wide default OR opt out of auto-assignment
  ;; entirely (by setting `:default-assignee nil`).
  (testing ":default-assignee string in ctx wins over git/user-name"
    (with-redefs [git/user-name (fn [] "git-configured-user")]
      (with-tmp tmp
        (let [ctx*   (-> (ctx tmp)
                         (dissoc :assignee)
                         (assoc :default-assignee "alice"))
              path   (cli/create-cmd ctx* {:title "Hello"})
              id     (->> (fs/file-name path)
                          (re-matches #"(.+)--hello\.md")
                          second)
              loaded (store/load-one tmp ".tickets" id)]
          (is (= "alice" (:assignee (:frontmatter loaded))))))))

  (testing ":default-assignee nil in ctx suppresses git/user-name fallback"
    (with-redefs [git/user-name (fn [] "git-configured-user")]
      (with-tmp tmp
        (let [ctx*   (-> (ctx tmp)
                         (dissoc :assignee)
                         (assoc :default-assignee nil))
              path   (cli/create-cmd ctx* {:title "Hello"})
              id     (->> (fs/file-name path)
                          (re-matches #"(.+)--hello\.md")
                          second)
              loaded (store/load-one tmp ".tickets" id)]
          (is (not (contains? (:frontmatter loaded) :assignee))
              "no :assignee key should be written when :default-assignee is nil")))))

  (testing "explicit --assignee opt still wins over :default-assignee"
    (with-redefs [git/user-name (fn [] "git-configured-user")]
      (with-tmp tmp
        (let [ctx*   (-> (ctx tmp)
                         (dissoc :assignee)
                         (assoc :default-assignee "alice"))
              path   (cli/create-cmd ctx* {:title "Hello" :assignee "bob"})
              id     (->> (fs/file-name path)
                          (re-matches #"(.+)--hello\.md")
                          second)
              loaded (store/load-one tmp ".tickets" id)]
          (is (= "bob" (:assignee (:frontmatter loaded)))))))))

(defmacro ^:private with-err-str
  "Capture writes to *err* during `body`, returning the captured string."
  [& body]
  `(let [sw# (java.io.StringWriter.)]
     (binding [*err* sw#]
       ~@body
       (str sw#))))

(deftest show-cmd-test
  (testing "show returns the rendered ticket content for a known id"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Hello"})
            id   (->> (fs/file-name path)
                      (re-matches #"(.+)--hello\.md")
                      second)
            out  (cli/show-cmd (ctx tmp) {:id id})]
        (is (string? out))
        (is (str/includes? out (str "id: " id)))
        (is (str/includes? out "title: Hello")))))

  (testing "show returns nil when no ticket matches the id"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/show-cmd (ctx tmp) {:id "nope-x"})))))

  (testing "show with :json? true returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Hello"})
            id   (->> (fs/file-name path)
                      (re-matches #"(.+)--hello\.md")
                      second)
            out  (cli/show-cmd (ctx tmp) {:id id :json? true})]
        (is (string? out))
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out (str "\"id\":\"" id "\"")))
        (is (str/includes? out "\"status\":\"open\""))
        (is (str/includes? out "\"body\""))
        (is (not (str/includes? out "---\n")))))))

(deftest ls-cmd-test
  (testing "ls returns a table string with column headers"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "First ticket"})
      (cli/create-cmd (ctx tmp) {:title "Second ticket"})
      (let [out (cli/ls-cmd (ctx tmp) {:tty? false :color? false})]
        (is (string? out))
        (is (str/includes? out "ID"))
        (is (str/includes? out "STATUS"))
        (is (str/includes? out "TITLE"))
        (is (str/includes? out "First ticket"))
        (is (str/includes? out "Second ticket")))))

  (testing "ls with :json? true returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "First"})
      (cli/create-cmd (ctx tmp) {:title "Second"})
      (let [out (cli/ls-cmd (ctx tmp) {:json? true})]
        (is (string? out))
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"data\":["))
        (is (str/includes? out "\"status\":\"open\"")))))

  (testing "ls excludes terminal-status tickets by default"
    ;; create three tickets, then bypass cli/create to drop one as 'closed'
    ;; via store directly
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live one"})
      (cli/create-cmd (ctx tmp) {:title "Live two"})
      ;; manually save a closed ticket
      (require 'knot.store)
      (let [save! (resolve 'knot.store/save!)]
        (save! tmp ".tickets" "kno-closedid001" "closed-ticket"
               {:frontmatter {:id "kno-closedid001" :title "Closed ticket"
                              :status "closed" :type "task" :priority 2
                              :mode "hitl"}
                :body        ""}
               {:now "2026-04-28T10:00:00Z" :terminal-statuses #{"closed"}}))
      (let [out (cli/ls-cmd (ctx tmp) {:tty? false :color? false})]
        (is (str/includes? out "Live one"))
        (is (str/includes? out "Live two"))
        (is (not (str/includes? out "Closed ticket"))
            "default ls should hide terminal-status tickets"))))

  (testing "ls returns an empty-table (header only) when there are no live tickets"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out (cli/ls-cmd (ctx tmp) {:tty? false :color? false})]
        (is (str/includes? out "ID"))
        (is (str/includes? out "TITLE"))))))

(deftest ls-cmd-filter-test
  (testing "ls --mode afk filters out hitl tickets"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Afk task"  :mode "afk"})
      (cli/create-cmd (ctx tmp) {:title "Hitl task" :mode "hitl"})
      (let [out (cli/ls-cmd (ctx tmp)
                            {:tty? false :color? false :mode #{"afk"}})]
        (is (str/includes? out "Afk task"))
        (is (not (str/includes? out "Hitl task"))))))

  (testing "ls --type bug filters by type"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "A bug"     :type "bug"})
      (cli/create-cmd (ctx tmp) {:title "A feature" :type "feature"})
      (let [out (cli/ls-cmd (ctx tmp)
                            {:tty? false :color? false :type #{"bug"}})]
        (is (str/includes? out "A bug"))
        (is (not (str/includes? out "A feature"))))))

  (testing "ls --assignee filters by assignee"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "For alice" :assignee "alice"})
      (cli/create-cmd (ctx tmp) {:title "For bob"   :assignee "bob"})
      (let [out (cli/ls-cmd (ctx tmp)
                            {:tty? false :color? false :assignee #{"alice"}})]
        (is (str/includes? out "For alice"))
        (is (not (str/includes? out "For bob"))))))

  (testing "ls --tag filters by tag overlap"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Urgent one" :tags ["urgent"]})
      (cli/create-cmd (ctx tmp) {:title "Other one"  :tags ["calm"]})
      (let [out (cli/ls-cmd (ctx tmp)
                            {:tty? false :color? false :tag #{"urgent"}})]
        (is (str/includes? out "Urgent one"))
        (is (not (str/includes? out "Other one"))))))

  (testing "ls --status open --mode afk ANDs the filters"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Afk open"  :mode "afk"})
            b (cli/create-cmd (ctx tmp) {:title "Hitl open" :mode "hitl"})
            _ b
            _ a
            out (cli/ls-cmd (ctx tmp)
                            {:tty? false :color? false
                             :status #{"open"} :mode #{"afk"}})]
        (is (str/includes? out "Afk open"))
        (is (not (str/includes? out "Hitl open"))))))

  (testing "ls --status closed surfaces terminal tickets (overrides default live filter)"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live one"})
      (let [a-path (cli/create-cmd (ctx tmp) {:title "Will close"})
            a-id   (->> (fs/file-name a-path)
                        (re-matches #"(.+)--will-close\.md")
                        second)
            _      (cli/close-cmd (ctx tmp) {:id a-id})
            out    (cli/ls-cmd (ctx tmp)
                               {:tty? false :color? false :status #{"closed"}})]
        (is (str/includes? out "Will close"))
        (is (not (str/includes? out "Live one")))))))

(deftest ls-cmd-custom-statuses-color-test
  (testing "ls-cmd colors the active lane yellow under a custom :statuses ctx"
    (with-tmp tmp
      (let [custom-ctx (assoc (ctx tmp)
                              :statuses          ["open" "active" "review" "closed"]
                              :terminal-statuses #{"closed"}
                              :active-status     "active")
            a-path (cli/create-cmd custom-ctx {:title "Custom-active ticket"})
            a-id   (->> (fs/file-name a-path)
                        (re-matches #"(.+)--custom-active-ticket\.md")
                        second)
            b-path (cli/create-cmd custom-ctx {:title "Custom-review ticket"})
            b-id   (->> (fs/file-name b-path)
                        (re-matches #"(.+)--custom-review-ticket\.md")
                        second)
            _      (cli/start-cmd custom-ctx {:id a-id})
            _      (cli/status-cmd custom-ctx {:id b-id :status "review"})
            out    (cli/ls-cmd custom-ctx {:tty? false :color? true :width 200})]
        (is (re-find #"\[33mactive" out)
            "active-status lane (\"active\") wraps in :yellow SGR (33)")
        (is (not (re-find #"\[33min_progress" out))
            "literal in_progress is not colored — config-driven, not literal-driven")
        (is (not (re-find #"\[[0-9;]+mreview" out))
            "non-special status (\"review\") receives no SGR end-to-end through the CLI")))))

(deftest list-cmds-thread-status-context-test
  ;; ls-cmd is covered in ls-cmd-custom-statuses-color-test; this test pins the
  ;; symmetric wiring for ready-cmd, closed-cmd, and blocked-cmd so a future
  ;; refactor that drops :statuses/:active-status from any one of those four
  ;; sites is caught by tests rather than by users with custom :statuses.
  (testing "ready-cmd threads the active-status ctx (active lane → yellow)"
    (with-tmp tmp
      (let [custom-ctx (assoc (ctx tmp)
                              :statuses          ["open" "active" "review" "closed"]
                              :terminal-statuses #{"closed"}
                              :active-status     "active")
            a-path (cli/create-cmd custom-ctx {:title "Ready-active ticket"})
            a-id   (->> (fs/file-name a-path)
                        (re-matches #"(.+)--ready-active-ticket\.md")
                        second)
            _      (cli/start-cmd custom-ctx {:id a-id})
            out    (cli/ready-cmd custom-ctx {:tty? false :color? true :width 200})]
        (is (re-find #"\[33mactive" out)
            "ready-cmd colors the :active-status row yellow")
        (is (not (re-find #"\[33min_progress" out))
            "ready-cmd does not color a literal in_progress"))))

  (testing "closed-cmd threads the terminal-statuses ctx (custom terminal lane → dim)"
    ;; Use a non-default terminal-status name ("done") so this test would
    ;; catch a wiring drop: under the v0 fallback (#{"closed"}), "done"
    ;; would not be recognized as terminal and would render uncolored.
    (with-tmp tmp
      (let [custom-ctx (assoc (ctx tmp)
                              :statuses          ["open" "active" "done"]
                              :terminal-statuses #{"done"}
                              :active-status     "active")
            a-path (cli/create-cmd custom-ctx {:title "Done ticket"})
            a-id   (->> (fs/file-name a-path)
                        (re-matches #"(.+)--done-ticket\.md")
                        second)
            _      (cli/close-cmd custom-ctx {:id a-id})
            out    (cli/closed-cmd custom-ctx {:tty? false :color? true :width 200})]
        (is (re-find #"\[2mdone" out)
            "closed-cmd colors a custom terminal status (\"done\") dim"))))

  (testing "blocked-cmd threads the active-status ctx (active lane → yellow)"
    (with-tmp tmp
      (let [custom-ctx (assoc (ctx tmp)
                              :statuses          ["open" "active" "review" "closed"]
                              :terminal-statuses #{"closed"}
                              :active-status     "active")
            a-path (cli/create-cmd custom-ctx {:title "Blocker ticket"})
            b-path (cli/create-cmd custom-ctx {:title "Blocked-active ticket"})
            a-id   (->> (fs/file-name a-path)
                        (re-matches #"(.+)--blocker-ticket\.md")
                        second)
            b-id   (->> (fs/file-name b-path)
                        (re-matches #"(.+)--blocked-active-ticket\.md")
                        second)
            _      (cli/dep-cmd custom-ctx {:from b-id :to a-id})
            _      (cli/start-cmd custom-ctx {:id b-id})
            out    (cli/blocked-cmd custom-ctx {:tty? false :color? true :width 200})]
        (is (re-find #"\[33mactive" out)
            "blocked-cmd colors the :active-status row yellow")
        (is (not (re-find #"\[33min_progress" out))
            "blocked-cmd does not color a literal in_progress")))))

(defn- id-of-created [path slug-pat]
  (->> (fs/file-name path)
       (re-matches (re-pattern (str "(.+)--" slug-pat "\\.md")))
       second))

(deftest show-cmd-broken-refs-warn-test
  (testing "show-cmd does not warn when refs all resolve"
    (with-tmp tmp
      (let [from (cli/create-cmd (ctx tmp) {:title "From"})
            to   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id (id-of-created from "from")
            to-id   (id-of-created to "to")
            _       (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            err     (with-err-str (cli/show-cmd (ctx tmp) {:id from-id}))]
        (is (= "" err)))))

  (testing "show-cmd warns to stderr on a broken :deps reference"
    (with-tmp tmp
      (let [from    (cli/create-cmd (ctx tmp) {:title "From"})
            from-id (id-of-created from "from")
            _       (cli/dep-cmd (ctx tmp) {:from from-id :to "kno-ghost"})
            err     (with-err-str (cli/show-cmd (ctx tmp) {:id from-id}))]
        (is (str/includes? err "kno-ghost"))
        (is (str/includes? err "missing")))))

  (testing "show-cmd warns to stderr on a broken :parent reference"
    (with-tmp tmp
      (let [t   (cli/create-cmd (ctx tmp) {:title "Hello" :parent "kno-ghost"})
            id  (id-of-created t "hello")
            err (with-err-str (cli/show-cmd (ctx tmp) {:id id}))]
        (is (str/includes? err "kno-ghost"))
        (is (str/includes? err "missing")))))

  (testing "show-cmd still returns the rendered ticket despite broken refs"
    (with-tmp tmp
      (let [from    (cli/create-cmd (ctx tmp) {:title "From"})
            from-id (id-of-created from "from")
            _       (cli/dep-cmd (ctx tmp) {:from from-id :to "kno-ghost"})
            out     (binding [*err* (java.io.StringWriter.)]
                      (cli/show-cmd (ctx tmp) {:id from-id}))]
        (is (str/includes? out "From"))
        (is (str/includes? out "kno-ghost")
            "raw :deps field including the broken id is in show output")))))

(deftest show-cmd-inverses-test
  (testing "show text includes ## Blockers when this ticket has :deps"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id a-id}))]
        (is (str/includes? out "## Blockers"))
        (is (str/includes? out (str "- " b-id "  Beta"))))))

  (testing "show text includes ## Blocking on the dep target"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id b-id}))]
        (is (str/includes? out "## Blocking"))
        (is (str/includes? out (str "- " a-id "  Alpha"))))))

  (testing "show text includes ## Children when other tickets parent into this"
    (with-tmp tmp
      (let [p (cli/create-cmd (ctx tmp) {:title "Parent"})
            p-id (id-of-created p "parent")
            c (cli/create-cmd (ctx tmp) {:title "Child" :parent p-id})
            c-id (id-of-created c "child")
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id p-id}))]
        (is (str/includes? out "## Children"))
        (is (str/includes? out (str "- " c-id "  Child"))))))

  (testing "computed sections are omitted when empty"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Solo"})
            a-id (id-of-created a "solo")
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id a-id}))]
        (is (not (str/includes? out "## Blockers")))
        (is (not (str/includes? out "## Blocking")))
        (is (not (str/includes? out "## Children")))
        (is (not (str/includes? out "## Linked"))))))

  (testing "broken :deps refs render with [missing] in the Blockers section"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            a-id (id-of-created a "alpha")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to "kno-ghost"})
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id a-id}))]
        (is (str/includes? out "## Blockers"))
        (is (str/includes? out "- kno-ghost  [missing]")))))

  (testing "show --json includes blockers/blocking/children/linked top-level fields"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            a-id (id-of-created a "alpha")
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id a-id :json? true}))]
        (is (str/includes? out "\"blockers\":[]"))
        (is (str/includes? out "\"blocking\":[]"))
        (is (str/includes? out "\"children\":[]"))
        (is (str/includes? out "\"linked\":[]")))))

  (testing "show --json populates the inverse fields with resolved entries"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (binding [*err* (java.io.StringWriter.)]
                   (cli/show-cmd (ctx tmp) {:id a-id :json? true}))]
        (is (str/includes? out (str "\"id\":\"" b-id "\"")))
        (is (str/includes? out "\"title\":\"Beta\""))))))

(deftest status-cmd-test
  (testing "status-cmd transitions a ticket to the given status"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-01T00:00:00Z")
            _       (cli/status-cmd later {:id id :status "in_progress"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "in_progress" (get-in loaded [:frontmatter :status])))
        (is (= "2026-05-01T00:00:00Z" (get-in loaded [:frontmatter :updated]))))))

  (testing "status-cmd transitioning to a terminal status sets :closed and archives"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-02T00:00:00Z")
            new-path (cli/status-cmd later {:id id :status "closed"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "closed" (get-in loaded [:frontmatter :status])))
        (is (= "2026-05-02T00:00:00Z" (get-in loaded [:frontmatter :closed])))
        (is (str/includes? new-path "/archive/"))
        (is (not (fs/exists? created))
            "live-directory file should be removed by archive auto-move"))))

  (testing "status-cmd allows arbitrary status names (any-to-any in v0)"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/status-cmd (ctx tmp) {:id id :status "review"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "review" (get-in loaded [:frontmatter :status]))))))

  (testing "status-cmd returns nil when no ticket matches the id"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/status-cmd (ctx tmp) {:id "nope-xx" :status "open"}))))))

(deftest status-cmd-json-test
  (testing "status-cmd with :json? returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            out     (cli/status-cmd (ctx tmp) {:id id :status "in_progress" :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (string? out))
        (is (= 1 (:schema_version parsed)))
        (is (= true (:ok parsed)))
        (is (= id (get-in parsed [:data :id])))
        (is (= "in_progress" (get-in parsed [:data :status])))
        (is (contains? (:data parsed) :body)
            "single-ticket envelope includes the body")
        (is (not (contains? parsed :meta))
            "non-terminal transition emits no :meta slot"))))

  (testing "status-cmd transition to terminal status sets meta.archived_to"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            out     (cli/status-cmd (ctx tmp) {:id id :status "closed" :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= "closed" (get-in parsed [:data :status])))
        (is (string? (get-in parsed [:meta :archived_to])))
        (is (str/includes? (get-in parsed [:meta :archived_to]) "/archive/")
            "meta.archived_to points at the archive subdirectory"))))

  (testing "status-cmd with :json? returns nil when no ticket matches"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/status-cmd (ctx tmp) {:id "nope-xx" :status "open" :json? true}))
          "json? does not change the not-found contract; the handler emits the envelope"))))

(deftest start-cmd-test
  (testing "start-cmd transitions a ticket to in_progress"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/start-cmd (ctx tmp) {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "in_progress" (get-in loaded [:frontmatter :status]))))))

  (testing "start-cmd transitions to a custom :active-status from config"
    (with-tmp tmp
      (let [ctx*    (assoc (ctx tmp)
                           :statuses          ["open" "active" "closed"]
                           :terminal-statuses #{"closed"}
                           :active-status     "active")
            created (cli/create-cmd ctx* {:title "T"})
            id      (id-of-created created "t")
            _       (cli/start-cmd ctx* {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "active" (get-in loaded [:frontmatter :status]))
            "start-cmd reads :active-status from ctx, not the literal in_progress")))))

(deftest start-cmd-json-test
  (testing "start-cmd with :json? threads through to the JSON envelope"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            out     (cli/start-cmd (ctx tmp) {:id id :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= id (get-in parsed [:data :id])))
        (is (= "in_progress" (get-in parsed [:data :status])))
        (is (not (contains? parsed :meta))
            "start never transitions to terminal — no :archived_to")))))

(deftest close-cmd-test
  (testing "close-cmd transitions to the first terminal status from :statuses"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            new-path (cli/close-cmd (ctx tmp) {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "closed" (get-in loaded [:frontmatter :status])))
        (is (str/includes? new-path "/archive/")))))

  (testing "close-cmd respects a custom :statuses + :terminal-statuses ordering"
    (with-tmp tmp
      (let [;; If statuses order is open → review → done → wontfix and both
            ;; done & wontfix are terminal, close-cmd picks done (the first
            ;; terminal in :statuses ordering).
            ctx* (assoc (ctx tmp)
                        :statuses ["open" "review" "done" "wontfix"]
                        :terminal-statuses #{"done" "wontfix"})
            created (cli/create-cmd ctx* {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd ctx* {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "done" (get-in loaded [:frontmatter :status])))))))

(deftest close-cmd-json-test
  (testing "close-cmd with :json? returns an envelope with meta.archived_to"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            out     (cli/close-cmd (ctx tmp) {:id id :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= id (get-in parsed [:data :id])))
        (is (= "closed" (get-in parsed [:data :status])))
        (is (str/includes? (get-in parsed [:meta :archived_to]) "/archive/")
            "close emits meta.archived_to pointing at the archive dir")
        (is (some? (get-in parsed [:data :closed]))
            "post-mutation ticket has :closed timestamp populated"))))

  (testing "close-cmd --summary --json includes the summary note in :data.body"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            out     (cli/close-cmd (ctx tmp) {:id id :summary "Shipped." :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (str/includes? (get-in parsed [:data :body]) "Shipped.")
            "summary note is appended in the body of the JSON envelope")))))

(deftest reopen-cmd-test
  (testing "reopen-cmd transitions a closed ticket to open and clears :closed"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd (ctx tmp) {:id id})
            ;; sanity: it's closed and archived
            after-close (store/load-one tmp ".tickets" id)
            _       (is (= "closed" (get-in after-close [:frontmatter :status])))
            new-path (cli/reopen-cmd (ctx tmp) {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "open" (get-in loaded [:frontmatter :status])))
        (is (not (contains? (:frontmatter loaded) :closed))
            "reopen should clear :closed entirely")
        (is (not (str/includes? new-path "/archive/"))
            "reopened file should live in the live directory again")))))

(deftest reopen-cmd-json-test
  (testing "reopen-cmd with :json? returns an envelope with status=open and no :archived_to"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd (ctx tmp) {:id id})
            out     (cli/reopen-cmd (ctx tmp) {:id id :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= "open" (get-in parsed [:data :status])))
        (is (not (contains? (:data parsed) :closed))
            "reopened ticket has no :closed in the JSON envelope")
        (is (not (contains? parsed :meta))
            "reopen never targets terminal — no :archived_to")))))

(deftest create-and-reopen-intake-status-from-config-test
  (testing "create-cmd derives the initial status from config (default → \"open\")"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp) {:title "Default intake"})
            id     (id-of-created path "default-intake")
            loaded (store/load-one tmp ".tickets" id)]
        (is (= "open" (get-in loaded [:frontmatter :status]))
            "default :statuses ⇒ first non-active, non-terminal is \"open\""))))

  (testing "create-cmd derives initial status from a custom :statuses lane"
    ;; Custom config with no "open" anywhere in :statuses — proves the
    ;; create-cmd path is config-driven and not coincidentally the literal "open".
    (with-tmp tmp
      (let [custom-ctx (assoc (ctx tmp)
                              :statuses          ["todo" "active" "done"]
                              :terminal-statuses #{"done"}
                              :active-status     "active")
            path   (cli/create-cmd custom-ctx {:title "Custom intake"})
            id     (id-of-created path "custom-intake")
            loaded (store/load-one tmp ".tickets" id)]
        (is (= "todo" (get-in loaded [:frontmatter :status]))
            "custom :statuses ⇒ first non-active, non-terminal is \"todo\"")
        (is (not= "open" (get-in loaded [:frontmatter :status]))
            "the literal \"open\" must not leak through when not in :statuses"))))

  (testing "reopen-cmd restores the same config-derived intake under custom :statuses"
    (with-tmp tmp
      (let [custom-ctx (assoc (ctx tmp)
                              :statuses          ["todo" "active" "done"]
                              :terminal-statuses #{"done"}
                              :active-status     "active")
            path    (cli/create-cmd custom-ctx {:title "Reopen target"})
            id      (id-of-created path "reopen-target")
            _       (cli/close-cmd custom-ctx {:id id})
            after-close (store/load-one tmp ".tickets" id)
            _       (is (= "done" (get-in after-close [:frontmatter :status]))
                        "sanity: close lands on the configured terminal")
            _       (cli/reopen-cmd custom-ctx {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "todo" (get-in loaded [:frontmatter :status]))
            "reopen restores the config-derived intake, not literal \"open\"")
        (is (not (contains? (:frontmatter loaded) :closed))
            "reopen still clears :closed")))))

(deftest summary-on-close-test
  (testing "close --summary appends a closure note under ## Notes and closes"
    (with-tmp tmp
      (let [created  (cli/create-cmd (ctx tmp) {:title "T"})
            id       (id-of-created created "t")
            later    (assoc (ctx tmp) :now "2026-05-01T10:00:00Z")
            new-path (cli/close-cmd later
                                    {:id id :summary "fixed in deploy 42"})
            loaded   (store/load-one tmp ".tickets" id)
            body     (:body loaded)]
        (is (= "closed" (get-in loaded [:frontmatter :status])))
        (is (str/includes? new-path "/archive/"))
        (is (str/includes? body "## Notes"))
        (is (str/includes? body "**2026-05-01T10:00:00Z**"))
        (is (str/includes? body "fixed in deploy 42")))))

  (testing "close with empty --summary still closes but adds no note"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd (ctx tmp) {:id id :summary ""})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "closed" (get-in loaded [:frontmatter :status])))
        (is (not (str/includes? (:body loaded) "## Notes"))
            "empty --summary should not create a Notes section"))))

  (testing "status <id> <terminal> --summary appends a note"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-02T10:00:00Z")
            _       (cli/status-cmd later
                                    {:id id :status "closed"
                                     :summary "shipped"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "closed" (get-in loaded [:frontmatter :status])))
        (is (str/includes? (:body loaded) "shipped"))
        (is (str/includes? (:body loaded) "**2026-05-02T10:00:00Z**")))))

  (testing "status <id> <non-terminal> --summary errors at command start"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)]
        (is (thrown-with-msg? Exception #"summary"
                              (cli/status-cmd (ctx tmp)
                                              {:id id :status "in_progress"
                                               :summary "should fail"})))
        (is (= before (slurp created))
            "no file change on rejected --summary"))))

  (testing "status <id> <non-terminal> --summary \"\" also errors (key-presence semantics)"
    ;; Validation triggers on (some? summary), not on (seq summary): an
    ;; empty-string summary on a non-terminal target is still a misuse.
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)]
        (is (thrown-with-msg? Exception #"summary"
                              (cli/status-cmd (ctx tmp)
                                              {:id id :status "in_progress" :summary ""})))
        (is (= before (slurp created))
            "no file change on rejected empty --summary"))))

  (testing "start --summary errors (start is always non-terminal)"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? Exception #"summary"
                              (cli/start-cmd (ctx tmp)
                                             {:id id :summary "should fail"}))))))

  (testing "reopen --summary errors (reopen is always non-terminal)"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd (ctx tmp) {:id id})]
        (is (thrown-with-msg? Exception #"summary"
                              (cli/reopen-cmd (ctx tmp)
                                              {:id id :summary "should fail"}))))))

  (testing "summary persists across reopen — historical journal"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd (ctx tmp)
                                   {:id id :summary "closure note"})
            _       (cli/reopen-cmd (ctx tmp) {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "open" (get-in loaded [:frontmatter :status])))
        (is (str/includes? (:body loaded) "closure note")
            "closure note remains in body after reopen"))))

  (testing "close --summary on a custom :statuses + :terminal-statuses still works"
    (with-tmp tmp
      (let [ctx*    (assoc (ctx tmp)
                           :statuses ["open" "review" "done" "wontfix"]
                           :terminal-statuses #{"done" "wontfix"})
            created (cli/create-cmd ctx* {:title "T"})
            id      (id-of-created created "t")
            _       (cli/close-cmd ctx* {:id id :summary "delivered"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "done" (get-in loaded [:frontmatter :status])))
        (is (str/includes? (:body loaded) "delivered"))))))

(deftest init-cmd-test
  (testing "writes .knot.edn with all default keys present, each commented"
    (with-tmp tmp
      (let [path (cli/init-cmd {:project-root tmp} {})]
        (is (= (str (fs/path tmp ".knot.edn")) path))
        (is (fs/exists? path))
        (let [content (slurp path)]
          ;; every known key should appear in the stub
          (doseq [k [":tickets-dir" ":prefix" ":default-assignee"
                     ":default-type" ":default-priority" ":statuses"
                     ":terminal-statuses" ":active-status"
                     ":types" ":modes" ":default-mode"]]
            (is (str/includes? content k)
                (str "stub should mention " k)))
          ;; the stub should be self-documenting (contain comments)
          (is (str/includes? content ";")
              "stub should include EDN line comments")))))

  (testing "stub :active-status defaults to in_progress and is uncommented"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))
            active-line (some (fn [l] (when (re-find #"^\s*:active-status\b" l) l))
                              (str/split-lines content))]
        (is (some? active-line)
            "stub has an uncommented :active-status line")
        (is (str/includes? active-line "\"in_progress\"")
            ":active-status defaults to in_progress"))))

  (testing "stub explains the constraint linking :active-status to :statuses"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))
            ;; The comment block right above :active-status names both
            ;; consumers (knot start / prime In Progress) and the
            ;; constraint (must be a non-terminal :statuses member).
            active-idx (str/index-of content ":active-status")
            preceding  (subs content 0 active-idx)
            ;; tail of the file before the line, looking back for comments
            preceding-tail (last (str/split preceding #"\n\n"))]
        (is (re-find #";;" preceding-tail)
            "explanatory comment precedes :active-status")
        (is (or (re-find #"(?i)knot start" preceding-tail)
                (re-find #"(?i)in progress" preceding-tail)
                (re-find #"(?i)active lane" preceding-tail))
            "comment names at least one consumer of :active-status"))))

  (testing "stub :afk-mode defaults to afk and explains its purpose"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))
            afk-line (some (fn [l] (when (re-find #"^\s*:afk-mode\b" l) l))
                           (str/split-lines content))
            afk-idx  (str/index-of content ":afk-mode")
            preceding (subs content 0 afk-idx)
            preceding-tail (last (str/split preceding #"\n\n"))]
        (is (some? afk-line)
            "stub has an uncommented :afk-mode line")
        (is (str/includes? afk-line "\"afk\"")
            ":afk-mode defaults to \"afk\"")
        (is (re-find #";;" preceding-tail)
            "explanatory comment precedes :afk-mode")
        (is (or (re-find #"(?i)autonomous" preceding-tail)
                (re-find #"(?i)agent" preceding-tail)
                (re-find #"(?i)preamble" preceding-tail))
            "comment names what :afk-mode controls (agent / autonomous / preamble)"))))

  (testing "stub :modes block warns against per-mode shortcut flags"
    ;; --mode <value> is the only path on `knot create`. Adding per-mode
    ;; shortcut flags (--afk, --hitl) bakes canonical mode names into
    ;; CLI parsing — projects that customize :modes would expose
    ;; shortcuts for modes they don't have. The init stub documents this
    ;; as a project-template invariant so :modes growth doesn't tempt
    ;; future agents into the same trap.
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))
            modes-idx (str/index-of content ":modes")
            preceding (subs content 0 modes-idx)
            preceding-tail (last (str/split preceding #"\n\n"))]
        (is (re-find #";;" preceding-tail)
            "explanatory comment precedes :modes")
        (is (re-find #"--mode" preceding-tail)
            ":modes comment points at --mode <value> as the canonical entry")
        (is (re-find #"(?i)shortcut" preceding-tail)
            ":modes comment names the per-mode-shortcut anti-pattern"))))

  (testing "creates the tickets-dir if missing"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {})
      (is (fs/directory? (fs/path tmp ".tickets")))))

  (testing "--prefix sets the :prefix value"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {:prefix "abc"})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))]
        (is (str/includes? content ":prefix \"abc\"")))))

  (testing "--tickets-dir sets the :tickets-dir value AND creates that dir"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {:tickets-dir "tasks"})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))]
        (is (str/includes? content ":tickets-dir \"tasks\"")))
      (is (fs/directory? (fs/path tmp "tasks")))
      (is (not (fs/directory? (fs/path tmp ".tickets")))
          "default .tickets/ should NOT be created when overridden")))

  (testing "without --force, aborts on existing .knot.edn (throws)"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn")) "{:default-type \"feature\"}")
      (is (thrown-with-msg? Exception #"already exists"
                            (cli/init-cmd {:project-root tmp} {})))
      (is (= "{:default-type \"feature\"}"
             (slurp (str (fs/path tmp ".knot.edn"))))
          "existing config should be untouched on abort")))

  (testing "--force overwrites an existing .knot.edn"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn")) "{:default-type \"feature\"}")
      (cli/init-cmd {:project-root tmp} {:force true})
      (let [content (slurp (str (fs/path tmp ".knot.edn")))]
        (is (str/includes? content ":default-priority"))
        (is (not= "{:default-type \"feature\"}" content)
            "existing config should be overwritten with --force"))))

  (testing "the generated stub is itself a valid .knot.edn that load-config accepts"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {})
      (is (= (config/defaults) (config/load-config tmp))
          "round-trip: load-config of the stub equals the defaults")))

  (testing "the stub generated with --prefix round-trips through load-config"
    (with-tmp tmp
      (cli/init-cmd {:project-root tmp} {:prefix "abc"})
      (is (= "abc" (:prefix (config/load-config tmp))))))

  (testing "init refuses an invalid --prefix before writing"
    (with-tmp tmp
      (is (thrown-with-msg? Exception #"prefix"
                            (cli/init-cmd {:project-root tmp} {:prefix "BAD!"})))
      (is (not (fs/exists? (fs/path tmp ".knot.edn")))
          "no stub should have been written on validation failure")
      (is (not (fs/directory? (fs/path tmp ".tickets")))
          "no tickets dir should have been created on validation failure")))

  (testing "init refuses an invalid --tickets-dir before writing"
    (with-tmp tmp
      (is (thrown-with-msg? Exception #"tickets-dir"
                            (cli/init-cmd {:project-root tmp} {:tickets-dir ""})))
      (is (not (fs/exists? (fs/path tmp ".knot.edn")))))))

(deftest dep-cmd-test
  (testing "dep-cmd writes the target id to from's :deps"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            to-path   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id   (id-of-created from-path "from")
            to-id     (id-of-created to-path "to")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            loaded    (store/load-one tmp ".tickets" from-id)]
        (is (= [to-id] (vec (get-in loaded [:frontmatter :deps])))))))

  (testing "dep-cmd is idempotent: adding the same dep twice doesn't duplicate"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            to-path   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id   (id-of-created from-path "from")
            to-id     (id-of-created to-path "to")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            loaded    (store/load-one tmp ".tickets" from-id)]
        (is (= [to-id] (vec (get-in loaded [:frontmatter :deps])))))))

  (testing "dep-cmd rejects a self-loop with the offending path"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            from-id   (id-of-created from-path "from")]
        (is (thrown-with-msg? Exception #"cycle"
                              (cli/dep-cmd (ctx tmp) {:from from-id :to from-id}))))))

  (testing "dep-cmd rejects an edge that would close a cycle"
    (with-tmp tmp
      (let [a-path (cli/create-cmd (ctx tmp) {:title "A"})
            b-path (cli/create-cmd (ctx tmp) {:title "B"})
            a-id   (id-of-created a-path "a")
            b-id   (id-of-created b-path "b")
            _      (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})]
        ;; now adding b -> a would close a cycle
        (try
          (cli/dep-cmd (ctx tmp) {:from b-id :to a-id})
          (is false "expected an exception")
          (catch Exception e
            (let [data (ex-data e)]
              (is (contains? data :cycle))
              (is (vector? (:cycle data)))
              (is (= b-id (first (:cycle data))))
              (is (= b-id (last (:cycle data))))))))))

  (testing "dep-cmd allows a forward ref to a non-existent ticket (broken refs are lenient)"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            from-id   (id-of-created from-path "from")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to "kno-ghost"})
            loaded    (store/load-one tmp ".tickets" from-id)]
        (is (= ["kno-ghost"] (vec (get-in loaded [:frontmatter :deps])))))))

  (testing "dep-cmd returns nil when from ticket does not exist"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/dep-cmd (ctx tmp) {:from "kno-nope" :to "kno-other"}))))))

(deftest dep-cmd-json-test
  (testing "dep-cmd with :json? returns the from ticket post-mutation, with updated :deps"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            to-path   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id   (id-of-created from-path "from")
            to-id     (id-of-created to-path "to")
            out       (cli/dep-cmd (ctx tmp) {:from from-id :to to-id :json? true})
            parsed    (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= from-id (get-in parsed [:data :id])))
        (is (= [to-id] (get-in parsed [:data :deps]))
            "data is the from ticket with the new :deps array"))))

  (testing "dep-cmd with :json? on idempotent re-add still returns the from ticket"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            to-path   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id   (id-of-created from-path "from")
            to-id     (id-of-created to-path "to")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            out       (cli/dep-cmd (ctx tmp) {:from from-id :to to-id :json? true})
            parsed    (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= [to-id] (get-in parsed [:data :deps]))
            "no duplicate entry on re-add")))))

(deftest undep-cmd-test
  (testing "undep-cmd removes the dep entry"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            to-path   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id   (id-of-created from-path "from")
            to-id     (id-of-created to-path "to")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            _         (cli/undep-cmd (ctx tmp) {:from from-id :to to-id})
            loaded    (store/load-one tmp ".tickets" from-id)]
        (is (not (contains? (:frontmatter loaded) :deps))
            "removing the last dep should drop the :deps key entirely"))))

  (testing "undep-cmd preserves other deps"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            b-path    (cli/create-cmd (ctx tmp) {:title "B"})
            c-path    (cli/create-cmd (ctx tmp) {:title "C"})
            from-id   (id-of-created from-path "from")
            b-id      (id-of-created b-path "b")
            c-id      (id-of-created c-path "c")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to b-id})
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to c-id})
            _         (cli/undep-cmd (ctx tmp) {:from from-id :to b-id})
            loaded    (store/load-one tmp ".tickets" from-id)]
        (is (= [c-id] (vec (get-in loaded [:frontmatter :deps])))))))

  (testing "undep-cmd is idempotent: removing a non-existent dep is a no-op"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            from-id   (id-of-created from-path "from")
            ;; no dep added; undep-cmd should be a clean no-op
            _         (cli/undep-cmd (ctx tmp) {:from from-id :to "kno-other"})
            loaded    (store/load-one tmp ".tickets" from-id)]
        (is (not (contains? (:frontmatter loaded) :deps))))))

  (testing "undep-cmd returns nil when from ticket does not exist"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/undep-cmd (ctx tmp) {:from "kno-nope" :to "kno-other"}))))))

(deftest undep-cmd-json-test
  (testing "undep-cmd with :json? returns the from ticket post-mutation"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            b-path    (cli/create-cmd (ctx tmp) {:title "B"})
            c-path    (cli/create-cmd (ctx tmp) {:title "C"})
            from-id   (id-of-created from-path "from")
            b-id      (id-of-created b-path "b")
            c-id      (id-of-created c-path "c")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to b-id})
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to c-id})
            out       (cli/undep-cmd (ctx tmp) {:from from-id :to b-id :json? true})
            parsed    (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= from-id (get-in parsed [:data :id])))
        (is (= [c-id] (get-in parsed [:data :deps]))
            "data is the from ticket with the dep removed"))))

  (testing "undep-cmd with :json? on the last dep drops the :deps key entirely"
    (with-tmp tmp
      (let [from-path (cli/create-cmd (ctx tmp) {:title "From"})
            to-path   (cli/create-cmd (ctx tmp) {:title "To"})
            from-id   (id-of-created from-path "from")
            to-id     (id-of-created to-path "to")
            _         (cli/dep-cmd (ctx tmp) {:from from-id :to to-id})
            out       (cli/undep-cmd (ctx tmp) {:from from-id :to to-id :json? true})
            parsed    (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= [] (get-in parsed [:data :deps]))
            "removing the last dep emits :deps as [] in the JSON envelope (disk YAML still pruned)")))))

(deftest ready-cmd-test
  (testing "ready-cmd lists open tickets with all-terminal deps"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha task"})
            b (cli/create-cmd (ctx tmp) {:title "Beta task"})
            a-id (id-of-created a "alpha-task")
            b-id (id-of-created b "beta-task")
            ;; depend Alpha on Beta, then close Beta — Alpha becomes ready
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            _    (cli/close-cmd (ctx tmp) {:id b-id})
            out  (cli/ready-cmd (ctx tmp) {:tty? false :color? false})]
        (is (str/includes? out "Alpha task")
            "ready ticket Alpha task appears in the table")
        (is (not (str/includes? out "Beta task"))
            "closed ticket Beta task does not appear"))))

  (testing "ready-cmd excludes blocked tickets"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha task"})
            b (cli/create-cmd (ctx tmp) {:title "Beta task"})
            a-id (id-of-created a "alpha-task")
            b-id (id-of-created b "beta-task")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            ;; Beta is open ⇒ Alpha is blocked
            out  (cli/ready-cmd (ctx tmp) {:tty? false :color? false})]
        (is (not (str/includes? out "Alpha task")) "blocked Alpha excluded")
        (is (str/includes? out "Beta task") "Beta has no deps, is ready"))))

  (testing "ready-cmd with :json? returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (let [out (cli/ready-cmd (ctx tmp) {:json? true})]
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"data\":["))
        (is (str/includes? out "\"status\":\"open\"")))))

  (testing "ready-cmd --mode afk excludes hitl tickets"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Afk job"  :mode "afk"})
      (cli/create-cmd (ctx tmp) {:title "Hitl job" :mode "hitl"})
      (let [out (cli/ready-cmd (ctx tmp)
                               {:tty? false :color? false :mode #{"afk"}})]
        (is (str/includes? out "Afk job"))
        (is (not (str/includes? out "Hitl job"))))))

  (testing "ready-cmd applies :mode filter BEFORE :limit truncation"
    ;; Three afk + three hitl tickets, all ready (no deps).
    ;; --limit 2 with --mode afk must return up to 2 *afk* tickets,
    ;; not 2 from the unfiltered ready set (which would have included hitl).
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Hitl one"   :mode "hitl"})
      (cli/create-cmd (ctx tmp) {:title "Hitl two"   :mode "hitl"})
      (cli/create-cmd (ctx tmp) {:title "Hitl three" :mode "hitl"})
      (cli/create-cmd (ctx tmp) {:title "Afk one"    :mode "afk"})
      (cli/create-cmd (ctx tmp) {:title "Afk two"    :mode "afk"})
      (cli/create-cmd (ctx tmp) {:title "Afk three"  :mode "afk"})
      (let [out (cli/ready-cmd (ctx tmp)
                               {:tty? false :color? false
                                :mode #{"afk"} :limit 2})
            afk-hits (count (re-seq #"Afk " out))
            hitl-hits (count (re-seq #"Hitl " out))]
        (is (= 2 afk-hits)
            "exactly two afk rows after limit applied to afk-only ready set")
        (is (zero? hitl-hits)
            "no hitl rows leak through when --mode afk is set"))))

  (testing "ready-cmd --limit without :mode caps the unfiltered ready set"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Job alpha"})
      (cli/create-cmd (ctx tmp) {:title "Job beta"})
      (cli/create-cmd (ctx tmp) {:title "Job gamma"})
      (let [out (cli/ready-cmd (ctx tmp)
                               {:tty? false :color? false :limit 2})
            hits (count (re-seq #"Job " out))]
        (is (= 2 hits))))))

(deftest freeform-body-round-trip-test
  (testing "freeform body sections survive save! → load → save! unchanged"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp) {:title "Bug"})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--bug\.md")
                        second)
            ;; replace body with freeform sections that knot does NOT
            ;; produce by default; their survival proves there is no
            ;; section enforcement.
            custom "# Bug

## Reproduction Steps

1. Click foo
2. Observe bar

## Expected vs Actual

Expected: green
Actual: red

## Workaround

Restart the daemon.
"
            loaded (store/load-one tmp ".tickets" id)
            ticket {:frontmatter (:frontmatter loaded) :body custom}
            _      (store/save! tmp ".tickets" id nil ticket
                                {:now "2026-04-28T11:00:00Z"
                                 :terminal-statuses #{"closed"}})
            re-loaded (store/load-one tmp ".tickets" id)]
        (is (= custom (:body re-loaded))
            "custom freeform sections preserved verbatim across save/load"))))

  (testing "edit-cmd preserves freeform sections (no enforcement on reload)"
    (with-tmp tmp
      (let [path   (cli/create-cmd (ctx tmp) {:title "Bug"})
            id     (->> (fs/file-name path)
                        (re-matches #"(.+)--bug\.md")
                        second)
            custom "# Bug\n\n## Reproduction Steps\n\nstep 1\nstep 2\n"
            ;; simulate an editor session by rewriting the on-disk file
            edit-fn (fn [p]
                      (let [existing (slurp p)
                            parsed   (ticket/parse existing)
                            edited   (assoc parsed :body custom)]
                        (spit p (ticket/render edited))))
            _      (cli/edit-cmd (ctx tmp) {:id id :editor-fn edit-fn})
            after  (store/load-one tmp ".tickets" id)]
        (is (= custom (:body after))
            "edit-cmd reload+resave does not strip custom body sections")))))

(deftest closed-cmd-test
  (testing "closed-cmd returns terminal-status tickets sorted by :closed desc"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Alpha"})
            b (cli/create-cmd c {:title "Beta"})
            d (cli/create-cmd c {:title "Gamma"})
            _ a _ b _ d
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            d-id (id-of-created d "gamma")
            ;; close each at a distinct timestamp; gamma closed last → first
            _    (cli/close-cmd (assoc c :now "2026-04-28T11:00:00Z") {:id a-id})
            _    (cli/close-cmd (assoc c :now "2026-04-28T12:00:00Z") {:id b-id})
            _    (cli/close-cmd (assoc c :now "2026-04-28T13:00:00Z") {:id d-id})
            out  (cli/closed-cmd c {:tty? false :color? false})
            ;; Beta is alphabetically before alpha and gamma — title order
            ;; can't accidentally produce the right ordering, so the order
            ;; of titles in the rendered table proves the :closed sort.
            ai   (str/index-of out "Alpha")
            bi   (str/index-of out "Beta")
            gi   (str/index-of out "Gamma")]
        (is (and ai bi gi) "all three closed tickets present")
        (is (< gi bi ai)
            "rows ordered Gamma → Beta → Alpha by :closed desc"))))

  (testing "closed-cmd with :limit caps result count, taking newest first"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Alpha"})
            b (cli/create-cmd c {:title "Beta"})
            d (cli/create-cmd c {:title "Gamma"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            d-id (id-of-created d "gamma")
            _    (cli/close-cmd (assoc c :now "2026-04-28T11:00:00Z") {:id a-id})
            _    (cli/close-cmd (assoc c :now "2026-04-28T12:00:00Z") {:id b-id})
            _    (cli/close-cmd (assoc c :now "2026-04-28T13:00:00Z") {:id d-id})
            out  (cli/closed-cmd c {:tty? false :color? false :limit 2})]
        (is (str/includes? out "Gamma") "newest closed kept")
        (is (str/includes? out "Beta")  "second-newest kept")
        (is (not (str/includes? out "Alpha"))
            "oldest closed dropped beyond --limit"))))

  (testing "closed-cmd without :limit returns every closed ticket"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Alpha"})
            b (cli/create-cmd c {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/close-cmd c {:id a-id})
            _    (cli/close-cmd c {:id b-id})
            out  (cli/closed-cmd c {:tty? false :color? false})]
        (is (str/includes? out "Alpha"))
        (is (str/includes? out "Beta")))))

  (testing "closed-cmd excludes live tickets"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Live one"})
            b (cli/create-cmd c {:title "Will close"})
            _ a
            b-id (id-of-created b "will-close")
            _    (cli/close-cmd c {:id b-id})
            out  (cli/closed-cmd c {:tty? false :color? false})]
        (is (str/includes? out "Will close"))
        (is (not (str/includes? out "Live one"))))))

  (testing "closed-cmd with :json? true returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Alpha"})
            a-id (id-of-created a "alpha")
            _    (cli/close-cmd c {:id a-id})
            out  (cli/closed-cmd c {:json? true})]
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"data\":["))
        (is (str/includes? out "\"status\":\"closed\"")))))

  (testing "closed-cmd returns an empty-table (header only) when none closed"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (cli/create-cmd (ctx tmp) {:title "Live"})
      (let [out (cli/closed-cmd (ctx tmp) {:tty? false :color? false})]
        (is (str/includes? out "ID"))
        (is (not (str/includes? out "Live"))))))

  (testing "closed-cmd sorts tickets without a :closed stamp last"
    ;; Locks in the missing-:closed branch of by-closed-desc. Legacy
    ;; archive files predating the :closed stamp must still load, but
    ;; they have no timestamp to compare against — they must sort after
    ;; every stamped ticket regardless of input order. Exercising this
    ;; branch via close-cmd is impossible (it always stamps), so the
    ;; legacy file is hand-written.
    (with-tmp tmp
      (let [c        (ctx tmp)
            stamped  (cli/create-cmd c {:title "Stamped"})
            stamped-id (id-of-created stamped "stamped")
            _        (cli/close-cmd (assoc c :now "2026-04-28T11:00:00Z")
                                    {:id stamped-id})
            archive  (fs/path tmp ".tickets" "archive")
            _        (fs/create-dirs archive)
            ;; Hand-rolled archive ticket: status is terminal, so closed-cmd
            ;; must include it, but no :closed key — exercises the comparator
            ;; branch where ca is nil.
            legacy   (str (fs/path archive "kno-legacy0001--legacy.md"))
            _        (spit legacy
                           (str "---\n"
                                "id: kno-legacy0001\n"
                                "title: Legacy\n"
                                "status: closed\n"
                                "type: task\n"
                                "priority: 2\n"
                                "created: 2025-01-01T00:00:00Z\n"
                                "updated: 2025-01-01T00:00:00Z\n"
                                "---\n"))
            out      (cli/closed-cmd c {:tty? false :color? false})
            stamped-i (str/index-of out "Stamped")
            legacy-i  (str/index-of out "Legacy")]
        (is (and stamped-i legacy-i)
            "both stamped and legacy tickets present in output")
        (is (< stamped-i legacy-i)
            "stamped ticket sorts before unstamped legacy ticket"))))

  (testing "closed-cmd sorts unstamped tickets last regardless of input order"
    ;; Cover the symmetric branch (cb nil) — load-all sorts by filename,
    ;; so put the unstamped file first alphabetically. If by-closed-desc
    ;; were `(compare cb ca)` unconditionally, nil compare would throw.
    (with-tmp tmp
      (let [c        (ctx tmp)
            archive  (fs/path tmp ".tickets" "archive")
            _        (fs/create-dirs archive)
            _        (spit (str (fs/path archive "kno-aaa00001--aaa.md"))
                           (str "---\n"
                                "id: kno-aaa00001\n"
                                "title: Unstamped\n"
                                "status: closed\n"
                                "type: task\n"
                                "priority: 2\n"
                                "created: 2025-01-01T00:00:00Z\n"
                                "updated: 2025-01-01T00:00:00Z\n"
                                "---\n"))
            stamped  (cli/create-cmd c {:title "Zeta"})
            zeta-id  (id-of-created stamped "zeta")
            _        (cli/close-cmd (assoc c :now "2026-04-28T11:00:00Z")
                                    {:id zeta-id})
            out      (cli/closed-cmd c {:tty? false :color? false})
            zeta-i   (str/index-of out "Zeta")
            unst-i   (str/index-of out "Unstamped")]
        (is (and zeta-i unst-i))
        (is (< zeta-i unst-i)
            "Zeta (stamped) sorts before Unstamped regardless of filename order"))))

  (testing "closed-cmd / ready-cmd reject --limit 0 and negative limits"
    ;; nil limit (the no-flag case) is the only non-positive value that
    ;; means 'no limit'. An explicit 0 or negative is almost always a
    ;; mistake and should fail loudly rather than be silently ignored.
    (with-tmp tmp
      (let [c (ctx tmp)]
        (cli/create-cmd c {:title "Live"})
        (doseq [n [0 -1 -42]
                cmd-fn [cli/closed-cmd cli/ready-cmd]]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"--limit must be a positive integer"
               (cmd-fn c {:tty? false :color? false :limit n}))
              (str cmd-fn " with limit " n " should throw"))))))

  (testing "closed-cmd / ready-cmd accept nil :limit (no-flag case)"
    ;; Sanity: the explicit-non-positive rejection above must not have
    ;; broken the common 'no --limit' code path.
    (with-tmp tmp
      (let [c (ctx tmp)
            _ (cli/create-cmd c {:title "Live"})]
        (is (string? (cli/closed-cmd c {:tty? false :color? false})))
        (is (string? (cli/ready-cmd  c {:tty? false :color? false})))))))

(deftest blocked-cmd-test
  (testing "blocked-cmd lists tickets with non-terminal deps"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha task"})
            b (cli/create-cmd (ctx tmp) {:title "Beta task"})
            a-id (id-of-created a "alpha-task")
            b-id (id-of-created b "beta-task")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (cli/blocked-cmd (ctx tmp) {:tty? false :color? false})]
        (is (str/includes? out "Alpha task") "blocked Alpha appears")
        (is (not (str/includes? out "Beta task")) "Beta has no deps, not blocked"))))

  (testing "blocked-cmd with :json? returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (cli/blocked-cmd (ctx tmp) {:json? true})]
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"data\":["))))))

(deftest check-cmd-clean-test
  (testing "check-cmd on a clean project returns exit 0 with an ok footer"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (let [{:keys [exit stdout stderr]} (cli/check-cmd (ctx tmp) {})]
        (is (zero? exit))
        (is (str/blank? stderr))
        (is (str/includes? stdout "ok"))
        (is (str/includes? stdout "scanned"))
        (is (str/includes? stdout "live=1"))
        (is (str/includes? stdout "archive=0"))))))

(deftest check-cmd-errors-test
  (testing "check-cmd with a hand-edited dep cycle returns exit 1 and a row per issue"
    (with-tmp tmp
      (let [a    (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b    (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            b-loaded (store/load-one tmp ".tickets" b-id)
            _ (store/save! tmp ".tickets" b-id nil
                           (assoc b-loaded :frontmatter
                                  (assoc (:frontmatter b-loaded) :deps [a-id]))
                           {:now "2026-04-28T11:00:00Z"
                            :terminal-statuses #{"closed"}})
            {:keys [exit stdout stderr]} (cli/check-cmd (ctx tmp) {})]
        (is (= 1 exit))
        (is (str/blank? stderr))
        (is (str/includes? stdout "dep_cycle"))
        (is (str/includes? stdout "SEVERITY"))
        (is (str/includes? stdout "CODE"))))))

(deftest check-cmd-json-clean-test
  (testing "check-cmd --json on a clean project: ok:true envelope, exit 0"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (let [{:keys [exit stdout stderr]} (cli/check-cmd (ctx tmp) {:json? true})
            parsed (cheshire.core/parse-string stdout true)]
        (is (zero? exit))
        (is (str/blank? stderr))
        (is (= 1   (:schema_version parsed)))
        (is (true? (:ok parsed)))
        (is (= [] (get-in parsed [:data :issues])))
        (is (= {:live 1 :archive 0} (get-in parsed [:data :scanned])))))))

(deftest check-cmd-json-errors-test
  (testing "check-cmd --json with errors: ok:false coexists with :data, exit 1"
    (with-tmp tmp
      (let [a    (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b    (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            b-loaded (store/load-one tmp ".tickets" b-id)
            _ (store/save! tmp ".tickets" b-id nil
                           (assoc b-loaded :frontmatter
                                  (assoc (:frontmatter b-loaded) :deps [a-id]))
                           {:now "2026-04-28T11:00:00Z"
                            :terminal-statuses #{"closed"}})
            {:keys [exit stdout]} (cli/check-cmd (ctx tmp) {:json? true})
            parsed (cheshire.core/parse-string stdout true)]
        (is (= 1   exit))
        (is (false? (:ok parsed)))
        (is (vector? (get-in parsed [:data :issues])))
        (is (some #(= "dep_cycle" (:code %)) (get-in parsed [:data :issues])))
        (is (not (contains? parsed :error)) ":error slot reserved for cannot-scan")))))

(deftest check-cmd-unknown-severity-test
  (testing "check-cmd rejects unknown --severity values, exit 2 with stderr (no JSON)"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (let [{:keys [exit stdout stderr]}
            (cli/check-cmd (ctx tmp) {:severity #{:loud}})]
        (is (= 2 exit))
        (is (str/blank? stdout))
        (is (str/includes? stderr "severity"))
        (is (str/includes? stderr "loud")))))

  (testing "check-cmd --json + unknown severity: stderr in both modes (arg-parsing-stays-on-stderr), exit 2"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (let [{:keys [exit stdout stderr]}
            (cli/check-cmd (ctx tmp) {:severity #{:loud} :json? true})]
        (is (= 2 exit))
        (is (str/blank? stdout) "no stdout under --json for arg-parse failures")
        (is (str/includes? stderr "severity"))
        (is (str/includes? stderr "loud"))))))

(deftest check-cmd-filter-test
  (testing "--code filter narrows issues; exit code reflects filtered view (grep semantics)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            b-loaded (store/load-one tmp ".tickets" b-id)
            _ (store/save! tmp ".tickets" b-id nil
                           (assoc b-loaded :frontmatter
                                  (assoc (:frontmatter b-loaded)
                                         :deps  [a-id]
                                         :links ["ghost"]))
                           {:now "2026-04-28T11:00:00Z"
                            :terminal-statuses #{"closed"}})
            {full-exit :exit}                  (cli/check-cmd (ctx tmp) {})
            {filt-exit :exit filt-out :stdout} (cli/check-cmd (ctx tmp)
                                                              {:code #{:does_not_exist}
                                                               :json? true})
            parsed (cheshire.core/parse-string filt-out true)]
        (is (= 1 full-exit) "errors present without filter -> exit 1")
        (is (= 0 filt-exit) "filter matches nothing -> exit 0 (clean view)")
        (is (true? (:ok parsed)))
        (is (= [] (get-in parsed [:data :issues])))))))

(deftest dep-tree-cmd-test
  (testing "dep-tree-cmd renders the root id and its deps"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (cli/dep-tree-cmd (ctx tmp) {:id a-id})]
        (is (string? out))
        (is (str/includes? out a-id))
        (is (str/includes? out "Alpha"))
        (is (str/includes? out b-id))
        (is (str/includes? out "Beta"))
        (is (str/includes? out "└──")))))

  (testing "dep-tree-cmd dedupes diamond branches by default (↑ on second occurrence)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            c (cli/create-cmd (ctx tmp) {:title "Gamma"})
            d (cli/create-cmd (ctx tmp) {:title "Delta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            c-id (id-of-created c "gamma")
            d-id (id-of-created d "delta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to c-id})
            _    (cli/dep-cmd (ctx tmp) {:from b-id :to d-id})
            _    (cli/dep-cmd (ctx tmp) {:from c-id :to d-id})
            out  (cli/dep-tree-cmd (ctx tmp) {:id a-id})]
        (is (str/includes? out "↑")
            "the second occurrence of Delta should be marked with ↑"))))

  (testing "dep-tree-cmd --full expands diamond branches fully (no ↑)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            c (cli/create-cmd (ctx tmp) {:title "Gamma"})
            d (cli/create-cmd (ctx tmp) {:title "Delta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            c-id (id-of-created c "gamma")
            d-id (id-of-created d "delta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to c-id})
            _    (cli/dep-cmd (ctx tmp) {:from b-id :to d-id})
            _    (cli/dep-cmd (ctx tmp) {:from c-id :to d-id})
            out  (cli/dep-tree-cmd (ctx tmp) {:id a-id :full? true})]
        (is (not (str/includes? out "↑"))
            "no ↑ markers in --full mode"))))

  (testing "dep-tree-cmd --json returns a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            a-id (id-of-created a "alpha")
            out  (cli/dep-tree-cmd (ctx tmp) {:id a-id :json? true})]
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out (str "\"id\":\"" a-id "\""))))))

  (testing "dep-tree-cmd renders [missing] for an unknown root id"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out (cli/dep-tree-cmd (ctx tmp) {:id "kno-ghost"})]
        (is (str/includes? out "kno-ghost"))
        (is (str/includes? out "[missing]")))))

  (testing "dep-tree-cmd warns to stderr for broken refs in the rendered subtree"
    (with-tmp tmp
      (let [a       (cli/create-cmd (ctx tmp) {:title "Alpha"})
            a-id    (id-of-created a "alpha")
            _       (cli/dep-cmd (ctx tmp) {:from a-id :to "kno-ghost"})
            err     (with-err-str (cli/dep-tree-cmd (ctx tmp) {:id a-id}))]
        (is (str/includes? err a-id) "stderr framing names the source ticket")
        (is (str/includes? err "kno-ghost"))
        (is (str/includes? err "missing")))))

  (testing "dep-tree-cmd does not warn when no refs are broken"
    (with-tmp tmp
      (let [a    (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b    (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            err  (with-err-str (cli/dep-tree-cmd (ctx tmp) {:id a-id}))]
        (is (= "" err))))))

(deftest link-cmd-test
  (testing "link-cmd writes a symmetric pair: each id appears in the other's :links"
    (with-tmp tmp
      (let [a-path (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b-path (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id   (id-of-created a-path "alpha")
            b-id   (id-of-created b-path "beta")
            _      (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            la     (store/load-one tmp ".tickets" a-id)
            lb     (store/load-one tmp ".tickets" b-id)]
        (is (= [b-id] (vec (get-in la [:frontmatter :links]))))
        (is (= [a-id] (vec (get-in lb [:frontmatter :links])))))))

  (testing "link-cmd with three ids creates symmetric links across every pair"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            c (cli/create-cmd (ctx tmp) {:title "Gamma"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            c-id (id-of-created c "gamma")
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id c-id]})
            la   (store/load-one tmp ".tickets" a-id)
            lb   (store/load-one tmp ".tickets" b-id)
            lc   (store/load-one tmp ".tickets" c-id)]
        (is (= #{b-id c-id} (set (get-in la [:frontmatter :links]))))
        (is (= #{a-id c-id} (set (get-in lb [:frontmatter :links]))))
        (is (= #{a-id b-id} (set (get-in lc [:frontmatter :links])))))))

  (testing "link-cmd is idempotent: relinking does not duplicate"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            la   (store/load-one tmp ".tickets" a-id)
            lb   (store/load-one tmp ".tickets" b-id)]
        (is (= [b-id] (vec (get-in la [:frontmatter :links]))))
        (is (= [a-id] (vec (get-in lb [:frontmatter :links]))))
        (is (= 1 (count (get-in la [:frontmatter :links])))
            "no duplicate entries on relink"))))

  (testing "link-cmd refuses fewer than two ids"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (thrown-with-msg? Exception #"two or more"
                            (cli/link-cmd (ctx tmp) {:ids ["only-one"]})))
      (is (thrown-with-msg? Exception #"two or more"
                            (cli/link-cmd (ctx tmp) {:ids []})))))

  (testing "link-cmd returns a vector of all saved paths (one per ticket)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            paths (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})]
        (is (vector? paths))
        (is (= 2 (count paths)))
        (is (every? #(str/ends-with? % ".md") paths)))))

  (testing "link-cmd preserves links across additional sessions (stable order)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            c (cli/create-cmd (ctx tmp) {:title "Gamma"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            c-id (id-of-created c "gamma")
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            _    (cli/link-cmd (ctx tmp) {:ids [a-id c-id]})
            la   (store/load-one tmp ".tickets" a-id)]
        (is (= [b-id c-id] (vec (get-in la [:frontmatter :links])))
            "subsequent links append; no reorder"))))

  (testing "link-cmd with :json? returns an array of post-mutation tickets"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            c (cli/create-cmd (ctx tmp) {:title "Gamma"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            c-id (id-of-created c "gamma")
            out  (cli/link-cmd (ctx tmp) {:ids [a-id b-id c-id] :json? true})
            parsed (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (vector? (:data parsed)))
        (is (= 3 (count (:data parsed))))
        (is (= #{a-id b-id c-id}
               (set (map :id (:data parsed))))
            "data array contains every touched ticket")
        (is (not (contains? (get-in parsed [:data 0]) :body))
            "body excluded from list-style envelope")
        (let [a-entry (first (filter #(= a-id (:id %)) (:data parsed)))]
          (is (= #{b-id c-id} (set (:links a-entry)))
              "each entry shows its post-mutation :links")))))

  (testing "link-cmd raises a clear error when an id does not resolve"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            a-id (id-of-created a "alpha")]
        (is (thrown-with-msg? Exception #"kno-ghost"
                              (cli/link-cmd (ctx tmp) {:ids [a-id "kno-ghost"]})))
        (is (not (contains? (:frontmatter (store/load-one tmp ".tickets" a-id))
                            :links))
            "all-or-nothing: a-id's :links must not be written when any target id fails to resolve")))))

(deftest unlink-cmd-test
  (testing "unlink-cmd removes the link from both files"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/link-cmd   (ctx tmp) {:ids [a-id b-id]})
            _    (cli/unlink-cmd (ctx tmp) {:from a-id :to b-id})
            la   (store/load-one tmp ".tickets" a-id)
            lb   (store/load-one tmp ".tickets" b-id)]
        (is (not (contains? (:frontmatter la) :links))
            "removing the only link should drop :links from a")
        (is (not (contains? (:frontmatter lb) :links))
            "removing the only link should drop :links from b"))))

  (testing "unlink-cmd preserves other links across the symmetric removal"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            c (cli/create-cmd (ctx tmp) {:title "Gamma"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            c-id (id-of-created c "gamma")
            ;; a↔b, a↔c, b↔c after link a b c
            _    (cli/link-cmd   (ctx tmp) {:ids [a-id b-id c-id]})
            _    (cli/unlink-cmd (ctx tmp) {:from a-id :to b-id})
            la   (store/load-one tmp ".tickets" a-id)
            lb   (store/load-one tmp ".tickets" b-id)
            lc   (store/load-one tmp ".tickets" c-id)]
        (is (= [c-id] (vec (get-in la [:frontmatter :links])))
            "a-c link survives the a-b removal")
        (is (= [c-id] (vec (get-in lb [:frontmatter :links])))
            "b-c link survives the a-b removal")
        (is (= #{a-id b-id} (set (get-in lc [:frontmatter :links])))
            "c is unaffected by the a-b removal"))))

  (testing "unlink-cmd is idempotent: removing a non-existent link is a no-op"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            ;; never linked; unlink should be a clean no-op
            _    (cli/unlink-cmd (ctx tmp) {:from a-id :to b-id})
            la   (store/load-one tmp ".tickets" a-id)
            lb   (store/load-one tmp ".tickets" b-id)]
        (is (not (contains? (:frontmatter la) :links)))
        (is (not (contains? (:frontmatter lb) :links))))))

  (testing "unlink-cmd raises a clear error when from does not resolve"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (thrown-with-msg? Exception #"kno-ghost"
                            (cli/unlink-cmd (ctx tmp) {:from "kno-ghost" :to "kno-other"})))))

  (testing "unlink-cmd returns a vector of saved paths"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            paths (cli/unlink-cmd (ctx tmp) {:from a-id :to b-id})]
        (is (vector? paths))
        (is (= 2 (count paths)))))))

(deftest unlink-cmd-json-test
  (testing "unlink-cmd with :json? returns array of post-mutation tickets (both touched)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            out  (cli/unlink-cmd (ctx tmp) {:from a-id :to b-id :json? true})
            parsed (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (vector? (:data parsed)))
        (is (= 2 (count (:data parsed))))
        (is (= #{a-id b-id} (set (map :id (:data parsed)))))
        (let [a-entry (first (filter #(= a-id (:id %)) (:data parsed)))]
          (is (= [] (:links a-entry))
              "removed last link emits :links as [] in envelope (disk YAML still pruned)")))))

  (testing "unlink-cmd with :json? returns just the from ticket when to does not exist"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/link-cmd (ctx tmp) {:ids [a-id b-id]})
            ;; Delete b's file so only a survives
            b-path (store/find-existing-path tmp ".tickets" b-id)
            _      (fs/delete b-path)
            out    (cli/unlink-cmd (ctx tmp) {:from a-id :to b-id :json? true})
            parsed (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= 1 (count (:data parsed)))
            "data is a 1-element array when :to does not resolve")
        (is (= a-id (get-in parsed [:data 0 :id])))))))

(deftest load-config-malformed-edn-test
  (testing "malformed EDN error includes the file path for context"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn")) "{:default-type \"task\"")  ;; missing }
      (is (thrown-with-msg? Exception #"\.knot\.edn at .* is not valid EDN"
                            (config/load-config tmp))))))

(deftest add-note-cmd-test
  (testing "explicit :text appends a timestamped note under ## Notes"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-01T10:00:00Z")
            path    (cli/add-note-cmd later {:id id :text "first note"})
            body    (:body (store/load-one tmp ".tickets" id))]
        (is (string? path))
        (is (str/includes? body "## Notes"))
        (is (str/includes? body "**2026-05-01T10:00:00Z**"))
        (is (str/includes? body "first note")))))

  (testing "explicit :text wins over stdin and editor"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/add-note-cmd (ctx tmp)
                                      {:id id :text "from-arg"
                                       :stdin-tty? false
                                       :stdin-reader-fn (fn [] "from-stdin")
                                       :editor-fn (fn [_] "from-editor")})
            body    (:body (store/load-one tmp ".tickets" id))]
        (is (str/includes? body "from-arg"))
        (is (not (str/includes? body "from-stdin")))
        (is (not (str/includes? body "from-editor"))))))

  (testing "stdin is read when :text is absent and stdin is not a TTY"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/add-note-cmd (ctx tmp)
                                      {:id id
                                       :stdin-tty? false
                                       :stdin-reader-fn (fn [] "piped note")
                                       :editor-fn (fn [_] "editor unused")})
            body    (:body (store/load-one tmp ".tickets" id))]
        (is (str/includes? body "piped note"))
        (is (not (str/includes? body "editor unused"))))))

  (testing "editor is opened when :text is absent and stdin is a TTY"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/add-note-cmd (ctx tmp)
                                      {:id id
                                       :stdin-tty? true
                                       :stdin-reader-fn (fn [] "stdin unused")
                                       :editor-fn (fn [_ctx-line] "edited content")})
            body    (:body (store/load-one tmp ".tickets" id))]
        (is (str/includes? body "edited content"))
        (is (not (str/includes? body "stdin unused"))))))

  (testing "empty :text cancels — file is not modified"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)
            result  (cli/add-note-cmd (ctx tmp) {:id id :text ""})
            after   (slurp created)]
        (is (nil? result))
        (is (= before after) "no file change on empty content")
        (is (not (str/includes? after "## Notes"))))))

  (testing "blank :text (whitespace only) cancels"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)
            _       (cli/add-note-cmd (ctx tmp) {:id id :text "   \n  "})
            after   (slurp created)]
        (is (= before after)))))

  (testing "blank stdin cancels"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)
            _       (cli/add-note-cmd (ctx tmp)
                                      {:id id
                                       :stdin-tty? false
                                       :stdin-reader-fn (fn [] "")})
            after   (slurp created)]
        (is (= before after)))))

  (testing "blank editor result cancels"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)
            _       (cli/add-note-cmd (ctx tmp)
                                      {:id id
                                       :stdin-tty? true
                                       :editor-fn (fn [_] "")})
            after   (slurp created)]
        (is (= before after)))))

  (testing "editor-fn that throws bubbles out and leaves the file untouched"
    ;; Pins the contract for the real spawn-editor! path: when the editor
    ;; exits non-zero (which knot.main turns into a thrown ex-info), the
    ;; exception propagates and no save runs.
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)]
        (is (thrown-with-msg? Exception #"editor crashed"
                              (cli/add-note-cmd (ctx tmp)
                                                {:id id
                                                 :stdin-tty? true
                                                 :editor-fn (fn [_]
                                                              (throw (ex-info "editor crashed" {})))})))
        (is (= before (slurp created))
            "no file change when editor-fn throws"))))

  (testing "appending a note bumps :updated"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-06-01T10:00:00Z")
            _       (cli/add-note-cmd later {:id id :text "x"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "2026-06-01T10:00:00Z" (get-in loaded [:frontmatter :updated]))))))

  (testing "leading and trailing newlines on note content are stripped"
    ;; Editor-mode pre-fill leaves leading blank lines after #-comments are
    ;; stripped; without trimming, the rendered note carries extra blanks
    ;; between the timestamp marker and the body.
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-10T10:00:00Z")
            _       (cli/add-note-cmd later
                                      {:id id :text "\n\nhello\n\n"})
            body    (:body (store/load-one tmp ".tickets" id))]
        (is (str/includes? body "**2026-05-10T10:00:00Z**\n\nhello\n"))
        (is (not (str/includes? body "**2026-05-10T10:00:00Z**\n\n\n"))
            "no triple-newline gap between timestamp and body"))))

  (testing "returns nil and does nothing when id is missing"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/add-note-cmd (ctx tmp) {:id "kno-nope" :text "x"}))))))

(deftest add-note-cmd-json-test
  (testing "add-note-cmd with :json? returns the post-mutation ticket including the appended note"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-01T10:00:00Z")
            out     (cli/add-note-cmd later {:id id :text "first note" :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (= id (get-in parsed [:data :id])))
        (is (str/includes? (get-in parsed [:data :body]) "first note")
            "appended note appears in :data.body")
        (is (str/includes? (get-in parsed [:data :body]) "**2026-05-01T10:00:00Z**")))))

  (testing "add-note-cmd with :json? on empty content remains a no-op (returns nil)"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            result  (cli/add-note-cmd (ctx tmp) {:id id :text "" :json? true})]
        (is (nil? result)
            "empty content cancellation does not emit an envelope; the handler decides"))))

  (testing "add-note-cmd with :json? returns nil when id is missing"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/add-note-cmd (ctx tmp) {:id "kno-nope" :text "x" :json? true}))))))

(deftest update-cmd-frontmatter-test
  (testing "update --title sets the title in frontmatter and bumps :updated"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "Original"})
            id      (id-of-created created "original")
            later   (assoc (ctx tmp) :now "2026-05-02T10:00:00Z")
            saved   (cli/update-cmd later {:id id :title "New Title"})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (string? saved))
        (is (= "New Title" (get-in loaded [:frontmatter :title])))
        (is (= "2026-05-02T10:00:00Z" (get-in loaded [:frontmatter :updated])))
        (is (= created saved)
            "slug-stable: filename unchanged when only title changes"))))

  (testing "update --type, --priority, --mode, --assignee replace those keys"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id
                                     :type "bug"
                                     :priority 0
                                     :mode "afk"
                                     :assignee "alice"})
            loaded  (store/load-one tmp ".tickets" id)
            fm      (:frontmatter loaded)]
        (is (= "bug" (:type fm)))
        (is (= 0 (:priority fm)))
        (is (= "afk" (:mode fm)))
        (is (= "alice" (:assignee fm))))))

  (testing "update --parent sets parent; --parent \"\" clears it"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp) {:id id :parent "kno-other"})
            after-1 (store/load-one tmp ".tickets" id)
            _       (cli/update-cmd (ctx tmp) {:id id :parent ""})
            after-2 (store/load-one tmp ".tickets" id)]
        (is (= "kno-other" (get-in after-1 [:frontmatter :parent])))
        (is (not (contains? (:frontmatter after-2) :parent))
            "blank --parent removes the key"))))

  (testing "update --assignee \"\" clears the assignee"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T" :assignee "alice"})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp) {:id id :assignee ""})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (not (contains? (:frontmatter loaded) :assignee))))))

  (testing "update --tags replaces the tag list; empty list clears"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T" :tags ["a" "b"]})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp) {:id id :tags ["x" "y" "z"]})
            after-1 (store/load-one tmp ".tickets" id)
            _       (cli/update-cmd (ctx tmp) {:id id :tags []})
            after-2 (store/load-one tmp ".tickets" id)]
        (is (= ["x" "y" "z"] (vec (get-in after-1 [:frontmatter :tags]))))
        (is (not (contains? (:frontmatter after-2) :tags))
            "empty --tags removes the key"))))

  (testing "update --external-ref replaces external_refs; empty list clears"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :external-ref ["JIRA-1"]})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :external-ref ["JIRA-2" "JIRA-3"]})
            after-1 (store/load-one tmp ".tickets" id)
            _       (cli/update-cmd (ctx tmp) {:id id :external-ref []})
            after-2 (store/load-one tmp ".tickets" id)]
        (is (= ["JIRA-2" "JIRA-3"]
               (vec (get-in after-1 [:frontmatter :external_refs]))))
        (is (not (contains? (:frontmatter after-2) :external_refs))))))

  (testing "update with no flags still bumps :updated"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-05-03T00:00:00Z")
            _       (cli/update-cmd later {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "2026-05-03T00:00:00Z" (get-in loaded [:frontmatter :updated])))
        (is (= "T" (get-in loaded [:frontmatter :title]))
            "untouched fields stay put"))))

  (testing "update returns nil when no ticket matches"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/update-cmd (ctx tmp) {:id "kno-nope" :title "X"})))))

  (testing "update on an ambiguous partial id throws"
    (with-tmp tmp
      ;; create two tickets so a 1-char prefix is ambiguous
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (cli/create-cmd (ctx tmp) {:title "Beta"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous id"
                            (cli/update-cmd (ctx tmp) {:id "kno-" :title "X"}))))))

(deftest update-cmd-body-sectional-test
  (testing "update --description replaces the Description section in place"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :description "Old description."})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :description "Brand new description."})
            loaded  (store/load-one tmp ".tickets" id)
            body    (:body loaded)]
        (is (str/includes? body "## Description"))
        (is (str/includes? body "Brand new description."))
        (is (not (str/includes? body "Old description."))))))

  (testing "update --description adds a new Description section when missing"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :description "First description."})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (str/includes? (:body loaded) "## Description"))
        (is (str/includes? (:body loaded) "First description.")))))

  (testing "update --design replaces the Design section"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T" :design "Old."})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp) {:id id :design "New design."})
            loaded  (store/load-one tmp ".tickets" id)
            body    (:body loaded)]
        (is (str/includes? body "## Design"))
        (is (str/includes? body "New design."))
        (is (not (str/includes? body "Old."))))))

  (testing "sectional update preserves other body sections (including Notes) and frontmatter acceptance"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :description "Desc."
                                     :design "Design."
                                     :acceptance ["AC item"]})
            id      (id-of-created created "t")
            ;; add a note so body has all sections
            _       (cli/add-note-cmd (ctx tmp) {:id id :text "kept"})
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :description "Replaced desc."})
            loaded  (store/load-one tmp ".tickets" id)
            body    (:body loaded)
            fm      (:frontmatter loaded)]
        (is (str/includes? body "Replaced desc."))
        (is (str/includes? body "## Design"))
        (is (str/includes? body "Design."))
        (is (not (str/includes? body "## Acceptance Criteria"))
            "AC lives in frontmatter under v0.3; never stored in the body")
        (is (= [{:title "AC item" :done false}] (:acceptance fm))
            "frontmatter acceptance survives sectional update")
        (is (str/includes? body "## Notes"))
        (is (str/includes? body "kept")
            "previous notes survive a sectional update")))))

(deftest update-cmd-body-replace-test
  (testing "update --body replaces the whole body"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :description "Old."
                                     :design "Old design."})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :body "Brand new freeform body.\n"})
            loaded  (store/load-one tmp ".tickets" id)
            body    (:body loaded)]
        (is (str/includes? body "Brand new freeform body."))
        (is (not (str/includes? body "## Description")))
        (is (not (str/includes? body "## Design"))))))

  (testing "update --body \"\" empties the body"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :description "Old."})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp) {:id id :body ""})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "" (:body loaded))))))

  (testing "update --body is mutually exclusive with --description / --design"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :body "x" :description "y"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :body "x" :design "y"})))))))

(deftest update-cmd-ac-flip-test
  (testing "update --ac \"<title>\" --done flips the matching entry to done"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :acceptance ["one" "two" "three"]})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :ac "two" :done true})
            loaded  (store/load-one tmp ".tickets" id)
            ac      (get-in loaded [:frontmatter :acceptance])]
        (is (= [{:title "one"   :done false}
                {:title "two"   :done true}
                {:title "three" :done false}]
               ac)
            "only the matching entry is flipped; order and other entries preserved"))))

  (testing "update --ac \"<title>\" --undone flips the matching entry to not done"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :acceptance ["only"]})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :ac "only" :done true})
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :ac "only" :undone true})
            loaded  (store/load-one tmp ".tickets" id)
            ac      (get-in loaded [:frontmatter :acceptance])]
        (is (= [{:title "only" :done false}] ac)))))

  (testing "update --ac matches by exact title (case-sensitive)"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :acceptance ["ship it"]})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no acceptance criterion"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :ac "Ship It" :done true}))
            "case mismatch is treated as no match"))))

  (testing "update --ac with a non-existent title throws an :ac-not-found error"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :acceptance ["one"]})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no acceptance criterion"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :ac "ghost" :done true}))))))

  (testing "update --done and --undone are mutually exclusive"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :acceptance ["x"]})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :ac "x"
                                               :done true :undone true}))))))

  (testing "update --ac requires exactly one of --done or --undone"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :acceptance ["x"]})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --done or --undone"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :ac "x"}))))))

  (testing "update --done without --ac is an error"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :acceptance ["x"]})
            id      (id-of-created created "t")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--done.*requires --ac"
                              (cli/update-cmd (ctx tmp)
                                              {:id id :done true}))))))

  (testing "update --ac flip preserves untouched frontmatter and body"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T"
                                     :description "Body desc."
                                     :tags ["a" "b"]
                                     :acceptance ["x" "y"]})
            id      (id-of-created created "t")
            _       (cli/update-cmd (ctx tmp)
                                    {:id id :ac "y" :done true})
            loaded  (store/load-one tmp ".tickets" id)
            fm      (:frontmatter loaded)]
        (is (= "T" (:title fm)))
        (is (= ["a" "b"] (:tags fm)))
        (is (str/includes? (:body loaded) "Body desc."))))))

(deftest update-cmd-json-test
  (testing "update --json returns a v0.3 envelope wrapping the post-mutation ticket"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "Original"})
            id      (id-of-created created "original")
            out     (cli/update-cmd (ctx tmp)
                                    {:id id
                                     :title "New"
                                     :priority 1
                                     :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (string? out))
        (is (= 1 (:schema_version parsed)))
        (is (= true (:ok parsed)))
        (is (= id (get-in parsed [:data :id])))
        (is (= "New" (get-in parsed [:data :title])))
        (is (= 1 (get-in parsed [:data :priority])))
        (is (contains? (:data parsed) :body)
            "single-ticket envelope includes the body")
        (is (not (contains? parsed :meta))
            "update never archives — no :meta slot"))))

  (testing "update --json with sectional body update reflects the new body"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp)
                                    {:title "T" :description "Old."})
            id      (id-of-created created "t")
            out     (cli/update-cmd (ctx tmp)
                                    {:id id
                                     :description "Brand new desc."
                                     :json? true})
            parsed  (cheshire/parse-string out true)]
        (is (= true (:ok parsed)))
        (is (str/includes? (get-in parsed [:data :body]) "Brand new desc.")))))

  (testing "update --json returns nil when no ticket matches"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/update-cmd (ctx tmp) {:id "kno-nope" :title "X" :json? true}))
          "json? does not change the not-found contract; the handler emits the envelope"))))

(deftest edit-cmd-test
  (testing "invokes the editor-fn with the ticket file path"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            seen    (atom nil)
            _       (cli/edit-cmd (ctx tmp)
                                  {:id id
                                   :editor-fn (fn [p] (reset! seen (str p)))})]
        (is (= created @seen)))))

  (testing "after the editor returns, the file is reloaded and saved (bumps :updated)"
    (with-tmp tmp
      (let [created  (cli/create-cmd (ctx tmp) {:title "T"})
            id       (id-of-created created "t")
            ;; mutate the file to add a body line, simulating an editor session
            _        (let [original (slurp created)]
                       (spit created (str original "\nNew line.\n")))
            later    (assoc (ctx tmp) :now "2026-06-15T10:00:00Z")
            new-path (cli/edit-cmd later {:id id :editor-fn (fn [_] nil)})
            loaded   (store/load-one tmp ".tickets" id)]
        (is (= "2026-06-15T10:00:00Z"
               (get-in loaded [:frontmatter :updated])))
        (is (str/includes? (:body loaded) "New line."))
        (is (= created new-path) "slug-stable: filename unchanged"))))

  (testing "no-op edit still bumps :updated (acceptable per PRD)"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            later   (assoc (ctx tmp) :now "2026-07-01T10:00:00Z")
            _       (cli/edit-cmd later {:id id :editor-fn (fn [_] nil)})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "2026-07-01T10:00:00Z"
               (get-in loaded [:frontmatter :updated]))))))

  (testing "returns nil when id is missing"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/edit-cmd (ctx tmp)
                              {:id "kno-nope" :editor-fn (fn [_] nil)})))))

  (testing "filename is preserved even when the title (and would-be slug) changes"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "Alpha"})
            id      (id-of-created created "alpha")
            ;; edit body to change title — slug must remain "alpha"
            _       (let [original (slurp created)]
                      (spit created (str/replace original "# Alpha" "# Bravo")))
            new-path (cli/edit-cmd (ctx tmp) {:id id :editor-fn (fn [_] nil)})]
        (is (= created new-path))
        (is (str/ends-with? new-path "--alpha.md")))))

  (testing "editor-fn that throws bubbles out and leaves the file untouched"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            before  (slurp created)]
        (is (thrown-with-msg? Exception #"editor crashed"
                              (cli/edit-cmd (ctx tmp)
                                            {:id id
                                             :editor-fn (fn [_]
                                                          (throw (ex-info "editor crashed" {})))})))
        (is (= before (slurp created))
            "no save runs when editor-fn throws")))))

(deftest resolve-editor-test
  (testing "VISUAL wins when set"
    (is (= "code -w" (cli/resolve-editor {"VISUAL" "code -w" "EDITOR" "vim"}))))
  (testing "EDITOR is used when VISUAL is unset"
    (is (= "vim" (cli/resolve-editor {"EDITOR" "vim"}))))
  (testing "blank VISUAL falls through to EDITOR"
    (is (= "vim" (cli/resolve-editor {"VISUAL" "" "EDITOR" "vim"}))))
  (testing "falls back to nano when both env vars are unset and nano is on PATH"
    (with-redefs [cli/on-path? #(= "nano" %)]
      (is (= "nano" (cli/resolve-editor {})))))
  (testing "falls back to vi when nano is not on PATH"
    (with-redefs [cli/on-path? (constantly false)]
      (is (= "vi" (cli/resolve-editor {}))))))

(defn- prime-ctx
  "Build a `ctx` for prime-cmd that mirrors `discover-ctx` with a
   project-found flag. `project-name` is forwarded to the renderer when set."
  ([tmp] (prime-ctx tmp nil))
  ([tmp project-name]
   (cond-> (assoc (ctx tmp) :project-found? true)
     project-name (assoc :project-name project-name))))

(deftest prime-cmd-text-shape-test
  (testing "prime-cmd emits Project + Ready + Commands when a project is found and tickets exist"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Live ticket"})
            a-id (id-of-created a "live-ticket")
            _    (cli/start-cmd c {:id a-id})
            out  (cli/prime-cmd (prime-ctx tmp) {})]
        (is (string? out))
        (is (str/includes? out "## Project"))
        (is (str/includes? out "## In Progress")
            "started ticket triggers In Progress section")
        (is (str/includes? out "## Ready"))
        (is (str/includes? out "## Commands"))
        (is (not (str/includes? out "## Schema"))
            "schema cheatsheet is retired"))))

  (testing "the rendered project metadata reflects live and archive counts"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Alpha"})
            _ (cli/create-cmd c {:title "Beta"})
            a-id (id-of-created a "alpha")
            _ (cli/close-cmd c {:id a-id})
            out (cli/prime-cmd (prime-ctx tmp) {})
            start (str/index-of out "## Project")
            end   (str/index-of out "## Ready")
            section (subs out start end)]
        (is (re-find #"live: 1" section))
        (is (re-find #"archive: 1" section))))))

(deftest prime-cmd-in-progress-active-status-test
  (testing "## In Progress section uses :active-status from config (custom statuses)"
    (with-tmp tmp
      (let [c (assoc (ctx tmp)
                     :statuses          ["open" "active" "closed"]
                     :terminal-statuses #{"closed"}
                     :active-status     "active")
            a (cli/create-cmd c {:title "Doing it"})
            a-id (id-of-created a "doing-it")
            _    (cli/start-cmd c {:id a-id})
            pctx (cond-> (assoc c :project-found? true))
            out  (cli/prime-cmd pctx {})]
        (is (str/includes? out "## In Progress")
            "ticket in the configured active status surfaces under In Progress")
        (is (str/includes? out "Doing it"))))))

(deftest prime-cmd-in-progress-sort-test
  (testing "in-progress section is sorted by :updated descending"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd (assoc c :now "2026-04-28T08:00:00Z")
                              {:title "Older started"})
            b (cli/create-cmd (assoc c :now "2026-04-28T09:00:00Z")
                              {:title "Newer started"})
            a-id (id-of-created a "older-started")
            b-id (id-of-created b "newer-started")
            _    (cli/start-cmd (assoc c :now "2026-04-28T10:00:00Z") {:id a-id})
            _    (cli/start-cmd (assoc c :now "2026-04-28T11:00:00Z") {:id b-id})
            out  (cli/prime-cmd (prime-ctx tmp) {})
            start (str/index-of out "## In Progress")
            end   (str/index-of out "## Ready")
            section (subs out start end)
            newer-i (str/index-of section "Newer started")
            older-i (str/index-of section "Older started")]
        (is (and newer-i older-i) "both in-progress titles present")
        (is (< newer-i older-i)
            "newer-updated ticket renders first (sorted by :updated desc)")))))

(deftest prime-cmd-ready-sort-test
  (testing "ready section is sorted by priority asc then :created desc"
    (with-tmp tmp
      (let [c (ctx tmp)]
        (cli/create-cmd (assoc c :now "2026-04-28T08:00:00Z")
                        {:title "P2 older" :priority 2})
        (cli/create-cmd (assoc c :now "2026-04-28T09:00:00Z")
                        {:title "P2 newer" :priority 2})
        (cli/create-cmd (assoc c :now "2026-04-28T10:00:00Z")
                        {:title "P0 ticket" :priority 0})
        (let [out (cli/prime-cmd (prime-ctx tmp) {})
              start (str/index-of out "## Ready")
              end   (str/index-of out "## Commands")
              section (subs out start end)
              p0-i  (str/index-of section "P0 ticket")
              newer-i (str/index-of section "P2 newer")
              older-i (str/index-of section "P2 older")]
          (is (and p0-i newer-i older-i))
          (is (< p0-i newer-i older-i)
              "P0 first (lower priority wins), then P2 newer-created before P2 older"))))))

(deftest prime-cmd-mode-filter-test
  (testing "--mode afk filters the ready section to afk-only tickets"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Afk job"  :mode "afk"})
      (cli/create-cmd (ctx tmp) {:title "Hitl job" :mode "hitl"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {:mode "afk"})
            start (str/index-of out "## Ready")
            end   (str/index-of out "## Commands")
            section (subs out start end)]
        (is (str/includes? section "Afk job"))
        (is (not (str/includes? section "Hitl job"))
            "hitl ticket excluded from ready section under --mode afk")))))

(deftest prime-cmd-default-cap-and-truncation-test
  (testing "ready section is capped at 20 by default with a truncation footer"
    (with-tmp tmp
      (let [c (ctx tmp)]
        (dotimes [n 22]
          (cli/create-cmd c {:title (str "Ticket-" n)}))
        (let [out (cli/prime-cmd (prime-ctx tmp) {})
              start (str/index-of out "## Ready")
              end   (str/index-of out "## Commands")
              section (subs out start end)
              ticket-lines (->> (str/split-lines section)
                                (filter #(str/starts-with? % "kno-")))]
          (is (= 20 (count ticket-lines))
              "exactly 20 ticket rows when 22 tickets exist and limit is default")
          (is (str/includes? section "+2 more"))
          (is (str/includes? section "knot ready"))))))

  (testing "no truncation footer when ready count fits within the cap"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Just one"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {})]
        (is (not (str/includes? out "more (run"))
            "no truncation footer when ready section fits"))))

  (testing ":limit overrides the default cap"
    (with-tmp tmp
      (let [c (ctx tmp)]
        (dotimes [n 5]
          (cli/create-cmd c {:title (str "Ticket-" n)}))
        (let [out (cli/prime-cmd (prime-ctx tmp) {:limit 2})
              start (str/index-of out "## Ready")
              end   (str/index-of out "## Commands")
              section (subs out start end)
              ticket-lines (->> (str/split-lines section)
                                (filter #(str/starts-with? % "kno-")))]
          (is (= 2 (count ticket-lines))
              "explicit --limit 2 overrides the default cap")
          (is (str/includes? section "+3 more")))))))

(deftest prime-cmd-mode-applies-before-limit-test
  (testing "--mode afk filters BEFORE --limit truncation"
    (with-tmp tmp
      (let [c (ctx tmp)]
        (dotimes [n 3] (cli/create-cmd c {:title (str "Hitl-" n) :mode "hitl"}))
        (dotimes [n 4] (cli/create-cmd c {:title (str "Afk-" n)  :mode "afk"}))
        (let [out (cli/prime-cmd (prime-ctx tmp) {:mode "afk" :limit 2})
              start (str/index-of out "## Ready")
              end   (str/index-of out "## Commands")
              section (subs out start end)
              afk-hits  (count (re-seq #"Afk-" section))
              hitl-hits (count (re-seq #"Hitl-" section))]
          (is (= 2 afk-hits) "exactly 2 afk rows after filter+limit")
          (is (zero? hitl-hits) "no hitl rows leak through")
          ;; 4 afk total minus 2 shown = 2 remaining.
          (is (str/includes? section "+2 more")))))))

(deftest prime-cmd-archive-only-test
  (testing "an archive-only project drops In Progress entirely and shows an empty Ready section"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Will close"})
            a-id (id-of-created a "will-close")
            _    (cli/close-cmd c {:id a-id})
            out  (cli/prime-cmd (prime-ctx tmp) {})
            rd-start (str/index-of out "## Ready")
            ;; Slice Ready up to whichever section follows it: Recently
            ;; Closed appears when archive has entries, Commands otherwise.
            rd-end   (or (str/index-of out "## Recently Closed")
                         (str/index-of out "## Commands"))
            rd-section (subs out rd-start rd-end)]
        (is (not (str/includes? out "## In Progress"))
            "no in-progress tickets → no In Progress heading")
        (is (not (str/includes? rd-section "Will close"))
            "closed ticket does not appear in ready")
        (is (re-find #"archive: 1" out)
            "archive count reflects the closed ticket")))))

(deftest prime-cmd-empty-project-test
  (testing "an empty project (no tickets) emits Project + Ready but suppresses empty In Progress"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out (cli/prime-cmd (prime-ctx tmp) {})]
        (is (str/includes? out "## Project"))
        (is (str/includes? out "## Ready"))
        (is (not (str/includes? out "## In Progress"))
            "empty in-progress section is suppressed entirely")))))

(deftest prime-cmd-in-progress-section-toggle-test
  (testing "## In Progress heading is omitted when no in-progress tickets exist"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Open but not started"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {})]
        (is (not (str/includes? out "## In Progress"))
            "open ticket should not trigger an In Progress section"))))

  (testing "## In Progress heading appears when at least one ticket is in_progress"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Will start"})
            a-id (id-of-created a "will-start")
            _    (cli/start-cmd c {:id a-id})
            out  (cli/prime-cmd (prime-ctx tmp) {})]
        (is (str/includes? out "## In Progress"))
        (is (str/includes? out "Will start"))))))

(deftest prime-cmd-mode-afk-preamble-test
  (testing "prime --mode afk emits the autonomous-agent preamble"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live ticket"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {:mode "afk"})
            first-section (str/index-of out "## ")
            preamble (subs out 0 first-section)]
        (is (re-find #"(?i)autonomous|agent" preamble)
            "preamble shifts to autonomous framing under --mode afk")
        (is (re-find #"knot ready --mode afk" preamble)
            "preamble surfaces the candidate-enumeration command")
        (is (not (re-find #"what's next" preamble))
            "human intent phrases are dropped under --mode afk"))))

  (testing "prime without --mode keeps the human-oriented preamble"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live ticket"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {})
            first-section (str/index-of out "## ")
            preamble (subs out 0 first-section)]
        (is (re-find #"what's next" preamble)
            "default preamble retains human intent phrases"))))

  (testing "prime --mode hitl keeps the human-oriented preamble (hitl is the human default)"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live ticket"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {:mode "hitl"})
            first-section (str/index-of out "## ")
            preamble (subs out 0 first-section)]
        (is (re-find #"what's next" preamble)
            "hitl mode is the human default — same preamble as bare prime")))))

(deftest prime-cmd-afk-mode-config-driven-test
  (testing "custom :modes + :afk-mode reach the agent preamble under the configured mode name"
    (with-tmp tmp
      (let [c (assoc (ctx tmp)
                     :modes        ["robot" "human"]
                     :default-mode "human"
                     :afk-mode     "robot")
            _ (cli/create-cmd c {:title "Live ticket"})
            pctx (assoc c :project-found? true)
            out  (cli/prime-cmd pctx {:mode "robot"})
            first-section (str/index-of out "## ")
            preamble (subs out 0 first-section)]
        (is (re-find #"(?i)autonomous|agent" preamble)
            "configured agent mode reaches the autonomous preamble"))))

  (testing "with :afk-mode nil, no mode value picks up the agent preamble"
    (with-tmp tmp
      (let [c (assoc (ctx tmp) :afk-mode nil)
            _ (cli/create-cmd c {:title "Live ticket"})
            pctx (assoc c :project-found? true)
            out  (cli/prime-cmd pctx {:mode "afk"})
            first-section (str/index-of out "## ")
            preamble (subs out 0 first-section)]
        (is (re-find #"what's next" preamble)
            "config opt-out wins — even --mode afk gets the human preamble"))))

  (testing "no-project branch sources :afk-mode from (config/defaults), preserving back-compat"
    ;; The no-project branch in prime-cmd builds its data map without the
    ;; project ctx, so it must thread :afk-mode from defaults the same way
    ;; it does for :active-status. Without that thread, the renderer falls
    ;; back to defaults itself; either way the back-compat path stays green.
    (with-tmp tmp
      (let [no-proj-ctx {:project-found? false}
            out (cli/prime-cmd no-proj-ctx {:mode "afk"})]
        (is (str/includes? out "knot init")
            "no-project preamble still wins over the agent preamble when no project is found")))))

(deftest prime-cmd-stale-in-progress-test
  (testing "in-progress ticket whose :updated is >14 days before now is flagged [stale]"
    (with-tmp tmp
      (let [c-old (assoc (ctx tmp) :now "2026-04-01T10:00:00Z")
            a (cli/create-cmd c-old {:title "Stalled work"})
            a-id (id-of-created a "stalled-work")
            _    (cli/start-cmd c-old {:id a-id})
            ;; Fast-forward "now" by ~30 days so the ticket's :updated
            ;; (set during start) is older than the staleness threshold.
            c-now (assoc (ctx tmp) :now "2026-05-01T10:00:00Z")
            out   (cli/prime-cmd (assoc (prime-ctx tmp) :now "2026-05-01T10:00:00Z") {})
            ip-start (str/index-of out "## In Progress")
            ip-end   (str/index-of out "## Ready")
            section  (subs out ip-start ip-end)]
        (is (re-find #"\[stale\]" section)
            "30-day-old in-progress ticket is rendered with [stale] prefix"))))

  (testing "in-progress ticket whose :updated is recent is NOT flagged [stale]"
    (with-tmp tmp
      (let [c (assoc (ctx tmp) :now "2026-04-29T10:00:00Z")
            a (cli/create-cmd c {:title "Active work"})
            a-id (id-of-created a "active-work")
            _    (cli/start-cmd c {:id a-id})
            ;; "now" is the same day — well within 14 days of :updated.
            out  (cli/prime-cmd (assoc (prime-ctx tmp) :now "2026-04-29T11:00:00Z") {})]
        (is (not (str/includes? out "[stale]"))
            "fresh in-progress ticket is not flagged"))))

  (testing "JSON exposes stale:true for stalled in-progress tickets"
    (with-tmp tmp
      (let [c-old (assoc (ctx tmp) :now "2026-04-01T10:00:00Z")
            a (cli/create-cmd c-old {:title "Stalled work"})
            a-id (id-of-created a "stalled-work")
            _    (cli/start-cmd c-old {:id a-id})
            out  (cli/prime-cmd
                  (assoc (prime-ctx tmp) :now "2026-05-01T10:00:00Z")
                  {:json? true})]
        (is (str/includes? out "\"stale\":true")
            "JSON in_progress entry carries stale:true for old tickets"))))

  (testing "exactly 14 days old is stale (boundary lock)"
    (with-tmp tmp
      (let [c-old (assoc (ctx tmp) :now "2026-04-01T10:00:00Z")
            a (cli/create-cmd c-old {:title "Edge case"})
            a-id (id-of-created a "edge-case")
            _    (cli/start-cmd c-old {:id a-id})
            ;; 14 days exactly after the start (which set :updated).
            out  (cli/prime-cmd
                  (assoc (prime-ctx tmp) :now "2026-04-15T10:00:00Z")
                  {})]
        (is (str/includes? out "[stale]")
            "14d threshold is inclusive — at exactly 14 days a ticket is stale"))))

  (testing "13 days 23 hours is NOT stale (boundary lock)"
    (with-tmp tmp
      (let [c-old (assoc (ctx tmp) :now "2026-04-01T10:00:00Z")
            a (cli/create-cmd c-old {:title "Just under threshold"})
            a-id (id-of-created a "just-under-threshold")
            _    (cli/start-cmd c-old {:id a-id})
            out  (cli/prime-cmd
                  (assoc (prime-ctx tmp) :now "2026-04-15T09:00:00Z")
                  {})]
        (is (not (str/includes? out "[stale]"))
            "13d 23h is NOT stale — locks the boundary against future drift")))))

(deftest prime-cmd-recently-closed-test
  (testing "closing a ticket with --summary surfaces both title and summary in Recently Closed"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Shipped feature X"})
            a-id (id-of-created a "shipped-feature-x")
            _    (cli/close-cmd c {:id a-id :summary "shipped in #482"})
            out  (cli/prime-cmd (prime-ctx tmp) {})
            rc-start (str/index-of out "## Recently Closed")
            cm-start (str/index-of out "## Commands")]
        (is (some? rc-start) "Recently Closed section appears after closing")
        (let [section (subs out rc-start cm-start)]
          (is (str/includes? section "Shipped feature X")
              "title surfaces under Recently Closed")
          (is (str/includes? section "shipped in #482")
              "close --summary surfaces under the title")))))

  (testing "section is suppressed when no tickets have been closed"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Open ticket"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {})]
        (is (not (str/includes? out "## Recently Closed"))
            "no closes → no Recently Closed heading"))))

  (testing "Recently Closed is capped at 3 entries, newest first"
    (with-tmp tmp
      (let [c (ctx tmp)]
        (doseq [i (range 4)]
          (let [path (cli/create-cmd
                      (assoc c :now (format "2026-04-%02dT08:00:00Z" (+ 20 i)))
                      {:title (str "Cl-" i)})
                id   (id-of-created path (str "cl-" i))]
            (cli/close-cmd
             (assoc c :now (format "2026-04-%02dT09:00:00Z" (+ 20 i)))
             {:id id :summary (str "summary-" i)})))
        (let [out (cli/prime-cmd (prime-ctx tmp) {})
              rc-start (str/index-of out "## Recently Closed")
              cm-start (str/index-of out "## Commands")
              section  (subs out rc-start cm-start)]
          (is (str/includes? section "Cl-3")  "newest closed surfaces")
          (is (str/includes? section "Cl-2")  "second-newest surfaces")
          (is (str/includes? section "Cl-1")  "third-newest surfaces")
          (is (not (str/includes? section "Cl-0"))
              "fourth-oldest is dropped — cap is 3")))))

  (testing "JSON output exposes recently_closed array with snake_case keys"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Done"})
            a-id (id-of-created a "done")
            _    (cli/close-cmd c {:id a-id :summary "wrapped up"})
            out  (cli/prime-cmd (prime-ctx tmp) {:json? true})]
        (is (str/includes? out "\"recently_closed\""))
        (is (str/includes? out "\"summary\":\"wrapped up\""))
        (is (str/includes? out "\"title\":\"Done\"")))))

  (testing "Recently Closed surfaces the close --summary, not earlier progress notes"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Built it"})
            a-id (id-of-created a "built-it")
            _    (cli/start-cmd c {:id a-id})
            _    (cli/add-note-cmd
                  (assoc c :now "2026-04-29T10:00:00Z")
                  {:id a-id :text "halfway there"})
            _    (cli/close-cmd
                  (assoc c :now "2026-04-30T10:00:00Z")
                  {:id a-id :summary "shipped in #999"})
            out  (cli/prime-cmd (prime-ctx tmp) {})
            rc-start (str/index-of out "## Recently Closed")
            cm-start (str/index-of out "## Commands")
            section  (subs out rc-start cm-start)]
        (is (str/includes? section "shipped in #999")
            "the latest note (close summary) surfaces")
        (is (not (str/includes? section "halfway there"))
            "earlier progress notes are not surfaced as the summary")))))

(deftest prime-cmd-omits-schema-section-test
  (testing "prime text never emits the legacy ## Schema cheatsheet"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Whatever"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {})]
        (is (not (str/includes? out "## Schema"))
            "## Schema section was hardcoded; agents read .knot.edn or `knot prime --json` instead"))))

  (testing "prime --json never carried the schema cheatsheet (regression guard)"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Whatever"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {:json? true})]
        (is (not (str/includes? out "## Schema")))))))

(deftest prime-cmd-no-project-test
  (testing "with :project-found? false, prime-cmd still returns a string and points at `knot init`"
    (with-tmp tmp
      (let [no-proj-ctx (-> (ctx tmp) (dissoc :project-found?))
            out (cli/prime-cmd no-proj-ctx {})]
        (is (string? out))
        (is (str/includes? out "knot init")
            "preamble references `knot init` so the agent can bootstrap")))))

(deftest prime-cmd-json-test
  (testing "with :json? true, prime-cmd emits a v0.3 success-envelope JSON string"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Ready ticket"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {:json? true})]
        (is (str/starts-with? out "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"in_progress\""))
        (is (str/includes? out "\"ready\""))
        (is (str/includes? out "\"ready_truncated\""))
        (is (str/includes? out "\"ready_remaining\""))
        (is (str/includes? out "Ready ticket"))
        (is (not (str/includes? out "## Schema"))
            "JSON output omits the schema cheatsheet"))))

  (testing ":json? truncation flag and remaining count match the pre-limit ready set"
    (with-tmp tmp
      (let [c (ctx tmp)]
        (dotimes [n 5] (cli/create-cmd c {:title (str "T-" n)}))
        (let [out (cli/prime-cmd (prime-ctx tmp) {:json? true :limit 2})]
          (is (str/includes? out "\"ready_truncated\":true"))
          (is (str/includes? out "\"ready_remaining\":3")))))))

(deftest prime-cmd-project-name-from-config-test
  (testing "project name is rendered when ctx supplies :project-name"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live"})
      (let [out (cli/prime-cmd (prime-ctx tmp "my-project") {})
            start (str/index-of out "## Project")
            end   (str/index-of out "## Ready")
            section (subs out start end)]
        (is (str/includes? section "my-project")))))

  (testing "no project name line when :project-name is absent"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Live"})
      (let [out (cli/prime-cmd (prime-ctx tmp) {})
            start (str/index-of out "## Project")
            end   (str/index-of out "## Ready")
            section (subs out start end)]
        (is (not (re-find #"^name:" section))
            "name: line absent when :project-name not provided")))))

(defn- save-direct
  "Bypass cli/create-cmd to install a ticket with a deterministic id —
   `cli/create-cmd` derives the id from a millisecond clock, which makes
   short-prefix collision tests racy."
  [tmp id slug status]
  (store/save! tmp ".tickets" id slug
               {:frontmatter {:id id :title slug :status status :type "task"
                              :priority 2 :mode "hitl"}
                :body ""}
               {:now "2026-04-28T10:00:00Z" :terminal-statuses #{"closed"}}))

(deftest partial-id-resolution-test
  (testing "show-cmd resolves a unique prefix-of-full ID"
    (with-tmp tmp
      (save-direct tmp "kno-01abc111111" "alpha" "open")
      (save-direct tmp "kno-99zz000000"  "beta"  "open")
      (let [out (cli/show-cmd (ctx tmp) {:id "kno-01abc"})]
        (is (some? out))
        (is (str/includes? out "kno-01abc111111")))))

  (testing "show-cmd resolves a unique prefix-of-suffix (no project prefix)"
    (with-tmp tmp
      (save-direct tmp "kno-01abc111111" "alpha" "open")
      (save-direct tmp "kno-99zz000000"  "beta"  "open")
      (let [out (cli/show-cmd (ctx tmp) {:id "01abc"})]
        (is (str/includes? out "kno-01abc111111")))))

  (testing "show-cmd throws ex-info on ambiguous partial id"
    (with-tmp tmp
      (save-direct tmp "kno-01abc111111" "alpha" "open")
      (save-direct tmp "kno-01abc222222" "beta"  "open")
      (let [e (try (cli/show-cmd (ctx tmp) {:id "kno-01abc"})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= :ambiguous (:kind (ex-data e)))))))

  (testing "show-cmd still returns nil on not-found (preserves cli contract)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/show-cmd (ctx tmp) {:id "kno-nope"})))))

  (testing "status-cmd accepts a partial id"
    (with-tmp tmp
      (save-direct tmp "kno-01abc111111" "alpha" "open")
      (let [path (cli/status-cmd (ctx tmp) {:id "01abc" :status "in_progress"})
            loaded (store/load-one tmp ".tickets" "kno-01abc111111")]
        (is (some? path))
        (is (= "in_progress" (get-in loaded [:frontmatter :status]))))))

  (testing "add-note-cmd accepts a partial id"
    (with-tmp tmp
      (save-direct tmp "kno-01abc111111" "alpha" "open")
      (let [path   (cli/add-note-cmd (ctx tmp) {:id "01abc" :text "hello"})
            loaded (store/load-one tmp ".tickets" "kno-01abc111111")]
        (is (some? path))
        (is (str/includes? (:body loaded) "hello")))))

  (testing "edit-cmd accepts a partial id"
    (with-tmp tmp
      (save-direct tmp "kno-01abc111111" "alpha" "open")
      (let [path (cli/edit-cmd (ctx tmp) {:id "01abc" :editor-fn (fn [_] nil)})]
        (is (some? path))
        (is (str/includes? path "kno-01abc111111")))))

  (testing "dep-cmd accepts a partial id for both <from> and <to>"
    (with-tmp tmp
      (save-direct tmp "kno-aaaa11111111" "from-t" "open")
      (save-direct tmp "kno-bbbb22222222" "to-t"   "open")
      (let [path   (cli/dep-cmd (ctx tmp) {:from "aaaa" :to "bbbb"})
            loaded (store/load-one tmp ".tickets" "kno-aaaa11111111")]
        (is (some? path))
        (is (= ["kno-bbbb22222222"]
               (vec (get-in loaded [:frontmatter :deps])))
            "dep should resolve <to> to its canonical full id"))))

  (testing "dep-cmd preserves a deliberately-broken <to> reference"
    (with-tmp tmp
      (save-direct tmp "kno-aaaa11111111" "from-t" "open")
      (let [path   (cli/dep-cmd (ctx tmp) {:from "aaaa" :to "future-id"})
            loaded (store/load-one tmp ".tickets" "kno-aaaa11111111")]
        (is (some? path))
        (is (= ["future-id"]
               (vec (get-in loaded [:frontmatter :deps])))
            "non-resolvable <to> should be stored verbatim — broken refs are tolerated"))))

  (testing "link-cmd accepts partial ids"
    (with-tmp tmp
      (save-direct tmp "kno-aaaa11111111" "a" "open")
      (save-direct tmp "kno-bbbb22222222" "b" "open")
      (cli/link-cmd (ctx tmp) {:ids ["aaaa" "bbbb"]})
      (let [a (store/load-one tmp ".tickets" "kno-aaaa11111111")
            b (store/load-one tmp ".tickets" "kno-bbbb22222222")]
        (is (= ["kno-bbbb22222222"]
               (vec (get-in a [:frontmatter :links]))))
        (is (= ["kno-aaaa11111111"]
               (vec (get-in b [:frontmatter :links])))))))

  (testing "dep-tree-cmd accepts a partial root id"
    (with-tmp tmp
      (save-direct tmp "kno-aaaa11111111" "root"  "open")
      (save-direct tmp "kno-bbbb22222222" "child" "open")
      (cli/dep-cmd (ctx tmp) {:from "kno-aaaa11111111" :to "kno-bbbb22222222"})
      (let [out (cli/dep-tree-cmd (ctx tmp) {:id "aaaa"})]
        (is (str/includes? out "kno-aaaa11111111"))
        (is (str/includes? out "kno-bbbb22222222"))))))

(deftest ls-cmd-limit-test
  (testing "ls-cmd with :limit caps the result count"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (cli/create-cmd (ctx tmp) {:title "Beta"})
      (cli/create-cmd (ctx tmp) {:title "Gamma"})
      (let [out  (cli/ls-cmd (ctx tmp) {:tty? false :color? false :limit 2})
            rows (->> (str/split-lines out)
                      (filter #(str/starts-with? % "kno-")))]
        (is (= 2 (count rows)) "exactly 2 rows with :limit 2"))))

  (testing "ls-cmd without :limit returns all tickets"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (cli/create-cmd (ctx tmp) {:title "Beta"})
      (cli/create-cmd (ctx tmp) {:title "Gamma"})
      (let [out (cli/ls-cmd (ctx tmp) {:tty? false :color? false})]
        (is (str/includes? out "Alpha"))
        (is (str/includes? out "Beta"))
        (is (str/includes? out "Gamma"))))))

(deftest blocked-cmd-filter-test
  (testing "blocked-cmd with :mode filters by mode"
    (with-tmp tmp
      (let [a       (cli/create-cmd (ctx tmp) {:title "Afk blocked"  :mode "afk"})
            b       (cli/create-cmd (ctx tmp) {:title "Hitl blocked" :mode "hitl"})
            blocker (cli/create-cmd (ctx tmp) {:title "Blocker"})
            a-id       (id-of-created a       "afk-blocked")
            b-id       (id-of-created b       "hitl-blocked")
            blocker-id (id-of-created blocker "blocker")
            _ (cli/dep-cmd (ctx tmp) {:from a-id :to blocker-id})
            _ (cli/dep-cmd (ctx tmp) {:from b-id :to blocker-id})
            out (cli/blocked-cmd (ctx tmp) {:tty? false :color? false :mode #{"afk"}})]
        (is (str/includes? out "Afk blocked"))
        (is (not (str/includes? out "Hitl blocked"))
            "hitl-mode blocked ticket filtered out"))))

  (testing "blocked-cmd with :type filters by type"
    (with-tmp tmp
      (let [a   (cli/create-cmd (ctx tmp) {:title "Bug blocked"  :type "bug"})
            b   (cli/create-cmd (ctx tmp) {:title "Task blocked" :type "task"})
            dep (cli/create-cmd (ctx tmp) {:title "Common dep"})
            a-id   (id-of-created a   "bug-blocked")
            b-id   (id-of-created b   "task-blocked")
            dep-id (id-of-created dep "common-dep")
            _ (cli/dep-cmd (ctx tmp) {:from a-id :to dep-id})
            _ (cli/dep-cmd (ctx tmp) {:from b-id :to dep-id})
            out (cli/blocked-cmd (ctx tmp) {:tty? false :color? false :type #{"bug"}})]
        (is (str/includes? out "Bug blocked"))
        (is (not (str/includes? out "Task blocked"))
            "task-type blocked ticket filtered out"))))

  (testing "blocked-cmd with :limit caps result count"
    (with-tmp tmp
      (let [dep (cli/create-cmd (ctx tmp) {:title "Common dep"})
            a   (cli/create-cmd (ctx tmp) {:title "Blocked A"})
            b   (cli/create-cmd (ctx tmp) {:title "Blocked B"})
            c   (cli/create-cmd (ctx tmp) {:title "Blocked C"})
            dep-id (id-of-created dep "common-dep")
            a-id   (id-of-created a   "blocked-a")
            b-id   (id-of-created b   "blocked-b")
            c-id   (id-of-created c   "blocked-c")
            _ (cli/dep-cmd (ctx tmp) {:from a-id :to dep-id})
            _ (cli/dep-cmd (ctx tmp) {:from b-id :to dep-id})
            _ (cli/dep-cmd (ctx tmp) {:from c-id :to dep-id})
            out  (cli/blocked-cmd (ctx tmp) {:tty? false :color? false :limit 2})
            rows (->> (str/split-lines out)
                      (filter #(str/starts-with? % "kno-")))]
        (is (= 2 (count rows)) ":limit 2 returns exactly 2 blocked tickets")))))

(deftest closed-cmd-filter-test
  (testing "closed-cmd with :mode filters by mode"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Afk closed"  :mode "afk"})
            b (cli/create-cmd c {:title "Hitl closed" :mode "hitl"})
            a-id (id-of-created a "afk-closed")
            b-id (id-of-created b "hitl-closed")
            _ (cli/close-cmd c {:id a-id})
            _ (cli/close-cmd c {:id b-id})
            out (cli/closed-cmd c {:tty? false :color? false :mode #{"afk"}})]
        (is (str/includes? out "Afk closed"))
        (is (not (str/includes? out "Hitl closed"))
            "hitl-mode closed ticket filtered out"))))

  (testing "closed-cmd with :type filters by type"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Bug done"  :type "bug"})
            b (cli/create-cmd c {:title "Task done" :type "task"})
            a-id (id-of-created a "bug-done")
            b-id (id-of-created b "task-done")
            _ (cli/close-cmd c {:id a-id})
            _ (cli/close-cmd c {:id b-id})
            out (cli/closed-cmd c {:tty? false :color? false :type #{"bug"}})]
        (is (str/includes? out "Bug done"))
        (is (not (str/includes? out "Task done"))
            "task-type closed ticket filtered out"))))

  (testing "closed-cmd filters apply before sort and limit"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd (assoc c :now "2026-04-28T10:00:00Z")
                              {:title "Bug A" :type "bug"})
            b (cli/create-cmd (assoc c :now "2026-04-28T11:00:00Z")
                              {:title "Task B" :type "task"})
            d (cli/create-cmd (assoc c :now "2026-04-28T12:00:00Z")
                              {:title "Bug C" :type "bug"})
            a-id (id-of-created a "bug-a")
            b-id (id-of-created b "task-b")
            d-id (id-of-created d "bug-c")
            _ (cli/close-cmd (assoc c :now "2026-04-28T13:00:00Z") {:id a-id})
            _ (cli/close-cmd (assoc c :now "2026-04-28T14:00:00Z") {:id b-id})
            _ (cli/close-cmd (assoc c :now "2026-04-28T15:00:00Z") {:id d-id})
            out (cli/closed-cmd c {:tty? false :color? false :type #{"bug"} :limit 1})]
        (is (str/includes? out "Bug C") "most-recently closed bug retained")
        (is (not (str/includes? out "Bug A")) "second-newest bug dropped by limit")
        (is (not (str/includes? out "Task B")) "task-type excluded by filter")))))

(deftest prime-cmd-filter-all-sections-test
  (testing "prime :type filter applies to in_progress section"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Bug in progress"  :type "bug"})
            b (cli/create-cmd c {:title "Task in progress" :type "task"})
            a-id (id-of-created a "bug-in-progress")
            b-id (id-of-created b "task-in-progress")
            _ (cli/start-cmd c {:id a-id})
            _ (cli/start-cmd c {:id b-id})
            out     (cli/prime-cmd (prime-ctx tmp) {:type #{"bug"}})
            ip-start (str/index-of out "## In Progress")
            rd-start (str/index-of out "## Ready")
            section  (subs out ip-start rd-start)]
        (is (str/includes? section "Bug in progress"))
        (is (not (str/includes? section "Task in progress"))
            "task-type in-progress ticket filtered from In Progress section"))))

  (testing "prime :type filter applies to recently_closed section"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Bug done"  :type "bug"})
            b (cli/create-cmd c {:title "Task done" :type "task"})
            a-id (id-of-created a "bug-done")
            b-id (id-of-created b "task-done")
            _ (cli/close-cmd c {:id a-id})
            _ (cli/close-cmd c {:id b-id})
            out      (cli/prime-cmd (prime-ctx tmp) {:type #{"bug"}})
            rc-start (str/index-of out "## Recently Closed")
            cm-start (str/index-of out "## Commands")
            section  (subs out rc-start cm-start)]
        (is (some? rc-start) "Recently Closed section present")
        (is (str/includes? section "Bug done"))
        (is (not (str/includes? section "Task done"))
            "task-type closed ticket filtered from Recently Closed section"))))

  (testing "prime :mode filter applies to in_progress section (not just ready)"
    (with-tmp tmp
      (let [c (ctx tmp)
            a (cli/create-cmd c {:title "Afk active"  :mode "afk"})
            b (cli/create-cmd c {:title "Hitl active" :mode "hitl"})
            a-id (id-of-created a "afk-active")
            b-id (id-of-created b "hitl-active")
            _ (cli/start-cmd c {:id a-id})
            _ (cli/start-cmd c {:id b-id})
            out     (cli/prime-cmd (prime-ctx tmp) {:mode "afk"})
            ip-start (str/index-of out "## In Progress")
            rd-start (str/index-of out "## Ready")
            section  (subs out ip-start rd-start)]
        (is (str/includes? section "Afk active"))
        (is (not (str/includes? section "Hitl active"))
            "hitl-mode in-progress ticket filtered from In Progress under --mode afk")))))

(deftest info-cmd-text-shape-test
  (testing "info-cmd returns a string with all five fixed section headings"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out (cli/info-cmd (ctx tmp) {})]
        (is (string? out))
        (is (str/includes? out "Project"))
        (is (str/includes? out "Paths"))
        (is (str/includes? out "Defaults"))
        (is (str/includes? out "Allowed Values"))
        (is (str/includes? out "Counts"))))))

(deftest info-cmd-json-envelope-test
  (testing "info-cmd with :json? true returns a v0.3 envelope with nested data sections"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            parsed (cheshire/parse-string out true)]
        (is (= 1 (:schema_version parsed)))
        (is (= true (:ok parsed)))
        (is (map? (:data parsed)))
        (is (every? (set (keys (:data parsed)))
                    [:project :paths :defaults :allowed_values :counts])
            "data carries all five nested section keys")))))

(deftest info-cmd-project-block-test
  (testing "project block carries knot_version, prefix, name (nullable), config_present"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c       (assoc (ctx tmp) :prefix "kno" :config-present? false)
            out     (cli/info-cmd c {:json? true})
            project (get-in (cheshire/parse-string out true) [:data :project])]
        (is (= "kno" (:prefix project)))
        (is (string? (:knot_version project)))
        (is (re-matches #"\d+\.\d+\.\d+" (:knot_version project)))
        (is (contains? project :name))
        (is (nil? (:name project)) "name is null when not configured")
        (is (= false (:config_present project))))))

  (testing "project.config_present is true when ctx says so, project.name passes through"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c       (assoc (ctx tmp)
                           :project-name "Knot"
                           :config-present? true)
            out     (cli/info-cmd c {:json? true})
            project (get-in (cheshire/parse-string out true) [:data :project])]
        (is (= "Knot" (:name project)))
        (is (= true (:config_present project)))))))

(deftest info-cmd-paths-block-test
  (testing "paths carries cwd, project_root, config_path, tickets_dir, tickets_path, archive_path"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c     (assoc (ctx tmp) :cwd tmp :tickets-dir ".tickets")
            out   (cli/info-cmd c {:json? true})
            paths (get-in (cheshire/parse-string out true) [:data :paths])]
        (is (= tmp (:cwd paths)))
        (is (= tmp (:project_root paths)))
        (is (= ".tickets" (:tickets_dir paths)))
        (is (= (str (fs/path tmp ".knot.edn"))   (:config_path paths))
            "config_path is the expected effective absolute path even when missing")
        (is (= (str (fs/path tmp ".tickets"))    (:tickets_path paths)))
        (is (= (str (fs/path tmp ".tickets" "archive")) (:archive_path paths))))))

  (testing "paths uses :tickets-dir from config (not the literal default) when overridden"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp "issues"))
      (let [c     (assoc (ctx tmp) :cwd tmp :tickets-dir "issues")
            out   (cli/info-cmd c {:json? true})
            paths (get-in (cheshire/parse-string out true) [:data :paths])]
        (is (= "issues" (:tickets_dir paths)))
        (is (= (str (fs/path tmp "issues"))            (:tickets_path paths)))
        (is (= (str (fs/path tmp "issues" "archive"))  (:archive_path paths)))))))

(deftest info-cmd-defaults-block-test
  (testing "defaults carries default_type, default_priority, default_mode (from config)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c        (-> (ctx tmp)
                         (assoc :default-type     "feature"
                                :default-priority 1
                                :default-mode     "afk"))
            out      (cli/info-cmd c {:json? true})
            defaults (get-in (cheshire/parse-string out true) [:data :defaults])]
        (is (= "feature" (:default_type defaults)))
        (is (= 1         (:default_priority defaults)))
        (is (= "afk"     (:default_mode defaults))))))

  (testing "default_assignee is config-only and stays null when unset"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (with-redefs [git/user-name (constantly "Git User")]
        (let [c        (ctx tmp)
              out      (cli/info-cmd c {:json? true})
              defaults (get-in (cheshire/parse-string out true) [:data :defaults])]
          (is (contains? defaults :default_assignee))
          (is (nil? (:default_assignee defaults))
              "default_assignee reflects only :default-assignee from config, never git fallback")))))

  (testing "default_assignee carries the config value when set"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c        (assoc (ctx tmp) :default-assignee "alice")
            out      (cli/info-cmd c {:json? true})
            defaults (get-in (cheshire/parse-string out true) [:data :defaults])]
        (is (= "alice" (:default_assignee defaults))))))

  (testing "effective_create_assignee falls back to git user.name when config :default-assignee is unset"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (with-redefs [git/user-name (constantly "Git User")]
        (let [c        (ctx tmp)
              out      (cli/info-cmd c {:json? true})
              defaults (get-in (cheshire/parse-string out true) [:data :defaults])]
          (is (= "Git User" (:effective_create_assignee defaults)))))))

  (testing "effective_create_assignee uses :default-assignee when set (config overrides git)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (with-redefs [git/user-name (constantly "Git User")]
        (let [c        (assoc (ctx tmp) :default-assignee "alice")
              out      (cli/info-cmd c {:json? true})
              defaults (get-in (cheshire/parse-string out true) [:data :defaults])]
          (is (= "alice" (:effective_create_assignee defaults)))))))

  (testing "effective_create_assignee is null when :default-assignee is explicitly nil (opt-out)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (with-redefs [git/user-name (constantly "Git User")]
        (let [c        (assoc (ctx tmp) :default-assignee nil)
              ;; assoc nil leaves the key contained — same as `.knot.edn` setting it to nil
              out      (cli/info-cmd c {:json? true})
              defaults (get-in (cheshire/parse-string out true) [:data :defaults])]
          (is (contains? defaults :effective_create_assignee))
          (is (nil? (:effective_create_assignee defaults))
              "explicit :default-assignee nil means 'no auto-assign', not 'fall back to git'"))))))

(deftest info-cmd-allowed-values-block-test
  (testing "allowed_values carries statuses, active_status, terminal_statuses, types, modes, afk_mode, priority_range"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out (cli/info-cmd (ctx tmp) {:json? true})
            av  (get-in (cheshire/parse-string out true) [:data :allowed_values])]
        (is (= ["open" "in_progress" "closed"] (:statuses av)))
        (is (= "in_progress"                   (:active_status av)))
        (is (= ["closed"]                      (:terminal_statuses av)))
        (is (= ["bug" "feature" "task" "epic" "chore"] (:types av)))
        (is (= ["afk" "hitl"]                  (:modes av)))
        (is (= "afk"                           (:afk_mode av))
            "afk_mode names which entry in :modes denotes the autonomous-agent role")
        (is (= {:min 0 :max 4}                 (:priority_range av))))))

  (testing "allowed_values surfaces a custom :afk-mode override"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c   (assoc (ctx tmp)
                       :modes        ["robot" "human"]
                       :default-mode "human"
                       :afk-mode     "robot")
            out (cli/info-cmd c {:json? true})
            av  (get-in (cheshire/parse-string out true) [:data :allowed_values])]
        (is (= "robot" (:afk_mode av))
            "user override of :afk-mode reaches info JSON"))))

  (testing "allowed_values reports :afk_mode as nil when explicitly opted out"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c   (assoc (ctx tmp) :afk-mode nil)
            out (cli/info-cmd c {:json? true})
            av  (get-in (cheshire/parse-string out true) [:data :allowed_values])]
        (is (contains? av :afk_mode)
            "afk_mode key is always present, even when nil")
        (is (nil? (:afk_mode av))
            "nil round-trips so consumers can detect opt-out"))))

  (testing "allowed_values preserves configured order across statuses, types, modes"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [c   (assoc (ctx tmp)
                       :statuses          ["open" "review" "in_progress" "done" "wontfix"]
                       :terminal-statuses #{"done" "wontfix"}
                       :active-status     "in_progress"
                       :types             ["task" "bug"]
                       :modes             ["hitl" "afk"])
            out (cli/info-cmd c {:json? true})
            av  (get-in (cheshire/parse-string out true) [:data :allowed_values])]
        (is (= ["open" "review" "in_progress" "done" "wontfix"] (:statuses av))
            "statuses preserve configured order")
        (is (= ["task" "bug"]   (:types av)) "types preserve configured order")
        (is (= ["hitl" "afk"]   (:modes av)) "modes preserve configured order")
        (is (= ["done" "wontfix"] (:terminal_statuses av))
            "terminal_statuses ordered by filtering :statuses in order — not the raw set"))))

  (testing "terminal_statuses is always an array, even when only one terminal status exists"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [out (cli/info-cmd (ctx tmp) {:json? true})
            av  (get-in (cheshire/parse-string out true) [:data :allowed_values])]
        (is (vector? (:terminal_statuses av)))))))

(deftest info-cmd-counts-block-test
  (testing "live_count, archive_count, total_count are top-level *.md file counts"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--a.md")) "")
      (spit (str (fs/path tmp ".tickets" "kno-01b--b.md")) "")
      (spit (str (fs/path tmp ".tickets" "kno-01c--c.md")) "")
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01x--x.md")) "")
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01y--y.md")) "")
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            counts (get-in (cheshire/parse-string out true) [:data :counts])]
        (is (= 3 (:live_count counts)))
        (is (= 2 (:archive_count counts)))
        (is (= 5 (:total_count counts)) "total = live + archive"))))

  (testing "non-md files in tickets dir are ignored"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--a.md")) "")
      (spit (str (fs/path tmp ".tickets" "README.txt")) "")
      (spit (str (fs/path tmp ".tickets" ".gitkeep")) "")
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            counts (get-in (cheshire/parse-string out true) [:data :counts])]
        (is (= 1 (:live_count counts)) "only *.md files counted")))))

(deftest info-cmd-counts-edge-cases-test
  (testing "missing tickets dir yields zero counts (no error)"
    (with-tmp tmp
      ;; deliberately do NOT create .tickets/
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            counts (get-in (cheshire/parse-string out true) [:data :counts])]
        (is (= 0 (:live_count counts)))
        (is (= 0 (:archive_count counts)))
        (is (= 0 (:total_count counts))))))

  (testing "missing archive dir yields zero archive_count, live still counts"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--a.md")) "")
      ;; no .tickets/archive/ subdirectory
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            counts (get-in (cheshire/parse-string out true) [:data :counts])]
        (is (= 1 (:live_count counts)))
        (is (= 0 (:archive_count counts)))
        (is (= 1 (:total_count counts))))))

  (testing "nested .md files inside subdirectories of tickets dir do not count toward live_count"
    ;; Top-level only — archive/ is the only subdir we recurse into, and
    ;; its contents go to archive_count, not live_count. Anything else
    ;; nested (random subfolders, archive/sub/) is invisible.
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "scratch"))
      (fs/create-dirs (fs/path tmp ".tickets" "archive" "deep"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--a.md")) "")
      (spit (str (fs/path tmp ".tickets" "scratch" "kno-01b--b.md")) "")
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01x--x.md")) "")
      (spit (str (fs/path tmp ".tickets" "archive" "deep" "kno-01y--y.md")) "")
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            counts (get-in (cheshire/parse-string out true) [:data :counts])]
        (is (= 1 (:live_count counts))
            "only top-level .md in tickets dir counts toward live_count")
        (is (= 1 (:archive_count counts))
            "only top-level .md in archive dir counts toward archive_count")))))

(deftest info-cmd-tolerates-malformed-tickets-test
  (testing "malformed *.md files in tickets dir do not break info and do count"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--good.md"))
            "---\nid: kno-01a\nstatus: open\ntitle: Good\n---\n\n# Good\n")
      (spit (str (fs/path tmp ".tickets" "kno-01b--broken-yaml.md"))
            "---\nthis is: { not valid : yaml }: : :\n---\n\n# broken\n")
      (spit (str (fs/path tmp ".tickets" "kno-01c--no-frontmatter.md"))
            "no frontmatter at all, just text\n")
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01x--archived-broken.md"))
            "---\n[][][]\n---\n")
      (let [out    (cli/info-cmd (ctx tmp) {:json? true})
            parsed (cheshire/parse-string out true)
            counts (get-in parsed [:data :counts])]
        (is (= true (:ok parsed)) "envelope still ok:true despite malformed tickets")
        (is (= 3 (:live_count counts)) "all three live *.md files counted, including malformed ones")
        (is (= 1 (:archive_count counts)))
        (is (= 4 (:total_count counts)))))))

(deftest migrate-ac-cmd-test
  (testing "lifts every body's `## Acceptance Criteria` section into frontmatter and strips the body section"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--needs-migration.md"))
            (str "---\n"
                 "id: kno-01a\n"
                 "title: Needs migration\n"
                 "status: open\n"
                 "---\n\n"
                 "## Description\n\nDesc.\n\n"
                 "## Acceptance Criteria\n\n"
                 "- [ ] first\n"
                 "- [x] second\n"))
      (let [result (cli/migrate-ac-cmd (ctx tmp) {})
            loaded (store/load-one tmp ".tickets" "kno-01a")
            fm     (:frontmatter loaded)
            body   (:body loaded)]
        (is (= 1 (:migrated result)))
        (is (= [{:title "first"  :done false}
                {:title "second" :done true}]
               (:acceptance fm)))
        (is (not (str/includes? body "## Acceptance Criteria")))
        (is (str/includes? body "## Description")
            "other body sections survive the migration"))))

  (testing "tickets without a `## Acceptance Criteria` section are not rewritten"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01b--no-ac.md"))
            "---\nid: kno-01b\ntitle: No AC\nstatus: open\n---\n\n## Description\n\nD.\n")
      (let [path  (str (fs/path tmp ".tickets" "kno-01b--no-ac.md"))
            mtime-before (fs/last-modified-time path)
            result       (cli/migrate-ac-cmd (ctx tmp) {})
            mtime-after  (fs/last-modified-time path)]
        (is (= 0 (:migrated result)))
        (is (= 1 (:unchanged result)))
        (is (= mtime-before mtime-after)
            "tickets with nothing to migrate are not rewritten"))))

  (testing "running migrate-ac twice is a no-op on the second run (idempotent)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--m.md"))
            (str "---\nid: kno-01a\ntitle: M\nstatus: open\n---\n\n"
                 "## Acceptance Criteria\n\n- [ ] one\n"))
      (let [first-result  (cli/migrate-ac-cmd (ctx tmp) {})
            second-result (cli/migrate-ac-cmd (ctx tmp) {})]
        (is (= 1 (:migrated first-result)))
        (is (= 0 (:migrated second-result))
            "second run finds nothing to migrate"))))

  (testing "migration walks both live and archive directories"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--live.md"))
            (str "---\nid: kno-01a\ntitle: Live\nstatus: open\n---\n\n"
                 "## Acceptance Criteria\n\n- [ ] live-item\n"))
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01b--archived.md"))
            (str "---\nid: kno-01b\ntitle: Archived\nstatus: closed\n---\n\n"
                 "## Acceptance Criteria\n\n- [x] archived-item\n"))
      (let [result (cli/migrate-ac-cmd (ctx tmp) {})]
        (is (= 2 (:migrated result))
            "both live and archived tickets get migrated")))))
