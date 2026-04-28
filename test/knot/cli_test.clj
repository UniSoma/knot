(ns knot.cli-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.cli :as cli]
            [knot.config :as config]
            [knot.git :as git]
            [knot.store :as store]))

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

  (testing "the body starts with # <title> and has no empty section placeholders"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Fix login bug"})
            body (slurp path)]
        (is (str/includes? body "# Fix login bug"))
        (is (not (str/includes? body "## Description")))
        (is (not (str/includes? body "## Design")))
        (is (not (str/includes? body "## Acceptance Criteria"))))))

  (testing "supplied --description writes a ## Description section"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "T" :description "Some text."})
            body (slurp path)]
        (is (str/includes? body "## Description"))
        (is (str/includes? body "Some text.")))))

  (testing "supplied --design and --acceptance write their sections"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp)
                                 {:title       "T"
                                  :design      "Design notes."
                                  :acceptance  "Pass when X."})
            body (slurp path)]
        (is (str/includes? body "## Design"))
        (is (str/includes? body "Design notes."))
        (is (str/includes? body "## Acceptance Criteria"))
        (is (str/includes? body "Pass when X.")))))

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

  (testing "rendered frontmatter has stable, human-readable key order (id first)"
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
        (is (= ["id" "status" "type" "priority" "mode" "created" "updated"]
               (vec (take 7 first-keys))))))))

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
        (is (str/includes? out "# Hello")))))

  (testing "show returns nil when no ticket matches the id"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (cli/show-cmd (ctx tmp) {:id "nope-x"})))))

  (testing "show with :json? true returns a bare JSON object string"
    (with-tmp tmp
      (let [path (cli/create-cmd (ctx tmp) {:title "Hello"})
            id   (->> (fs/file-name path)
                      (re-matches #"(.+)--hello\.md")
                      second)
            out  (cli/show-cmd (ctx tmp) {:id id :json? true})]
        (is (string? out))
        (is (str/starts-with? out "{"))
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

  (testing "ls with :json? true returns a bare JSON array"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "First"})
      (cli/create-cmd (ctx tmp) {:title "Second"})
      (let [out (cli/ls-cmd (ctx tmp) {:json? true})]
        (is (string? out))
        (is (str/starts-with? out "["))
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
               {:frontmatter {:id "kno-closedid001" :status "closed"
                              :type "task" :priority 2 :mode "hitl"}
                :body        "# Closed ticket\n"}
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

(deftest start-cmd-test
  (testing "start-cmd transitions a ticket to in_progress"
    (with-tmp tmp
      (let [created (cli/create-cmd (ctx tmp) {:title "T"})
            id      (id-of-created created "t")
            _       (cli/start-cmd (ctx tmp) {:id id})
            loaded  (store/load-one tmp ".tickets" id)]
        (is (= "in_progress" (get-in loaded [:frontmatter :status])))))))

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
                     ":terminal-statuses" ":types" ":modes" ":default-mode"]]
            (is (str/includes? content k)
                (str "stub should mention " k)))
          ;; the stub should be self-documenting (contain comments)
          (is (str/includes? content ";")
              "stub should include EDN line comments")))))

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

  (testing "ready-cmd with :json? returns a bare JSON array"
    (with-tmp tmp
      (cli/create-cmd (ctx tmp) {:title "Alpha"})
      (let [out (cli/ready-cmd (ctx tmp) {:json? true})]
        (is (str/starts-with? out "["))
        (is (str/includes? out "\"status\":\"open\""))))))

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

  (testing "blocked-cmd with :json? returns a bare JSON array"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            out  (cli/blocked-cmd (ctx tmp) {:json? true})]
        (is (str/starts-with? out "["))))))

(deftest dep-cycle-cmd-test
  (testing "dep-cycle-cmd returns an empty vector when there are no cycles"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})]
        (is (empty? (cli/dep-cycle-cmd (ctx tmp) {}))))))

  (testing "dep-cycle-cmd surfaces a pre-existing cycle introduced by hand-edit"
    ;; create two tickets, dep one on the other normally, then hand-edit
    ;; the second ticket's frontmatter to add a back-edge — bypassing
    ;; the cycle check that `dep-cmd` would have done at insert time.
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            ;; bypass cycle check by writing through store directly
            b-loaded (store/load-one tmp ".tickets" b-id)
            _ (store/save! tmp ".tickets" b-id nil
                           (assoc b-loaded :frontmatter
                                  (assoc (:frontmatter b-loaded) :deps [a-id]))
                           {:now "2026-04-28T11:00:00Z"
                            :terminal-statuses #{"closed"}})
            cycles (cli/dep-cycle-cmd (ctx tmp) {})]
        (is (= 1 (count cycles)))
        (let [c (set (first cycles))]
          (is (contains? c a-id))
          (is (contains? c b-id))))))

  (testing "dep-cycle-cmd ignores closed tickets (cycles among archived only do not count)"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            b (cli/create-cmd (ctx tmp) {:title "Beta"})
            a-id (id-of-created a "alpha")
            b-id (id-of-created b "beta")
            _    (cli/dep-cmd (ctx tmp) {:from a-id :to b-id})
            b-loaded (store/load-one tmp ".tickets" b-id)
            _ (store/save! tmp ".tickets" b-id nil
                           (assoc b-loaded :frontmatter
                                  (assoc (:frontmatter b-loaded) :deps [a-id]))
                           {:now "2026-04-28T11:00:00Z"
                            :terminal-statuses #{"closed"}})
            ;; close both — the cycle is now wholly within archived tickets
            _ (cli/close-cmd (ctx tmp) {:id a-id})
            _ (cli/close-cmd (ctx tmp) {:id b-id})]
        (is (empty? (cli/dep-cycle-cmd (ctx tmp) {})))))))

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

  (testing "dep-tree-cmd --json returns a bare JSON object"
    (with-tmp tmp
      (let [a (cli/create-cmd (ctx tmp) {:title "Alpha"})
            a-id (id-of-created a "alpha")
            out  (cli/dep-tree-cmd (ctx tmp) {:id a-id :json? true})]
        (is (str/starts-with? out "{"))
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

(deftest load-config-malformed-edn-test
  (testing "malformed EDN error includes the file path for context"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn")) "{:default-type \"task\"")  ;; missing }
      (is (thrown-with-msg? Exception #"\.knot\.edn at .* is not valid EDN"
            (config/load-config tmp))))))
