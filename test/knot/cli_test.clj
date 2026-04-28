(ns knot.cli-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.cli :as cli]
            [knot.config :as config]
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
      (is (nil? (cli/show-cmd (ctx tmp) {:id "nope-x"}))))))
