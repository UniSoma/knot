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
