(ns knot.integration-test
  "End-to-end tests that drive the real CLI via babashka.process. The
   project's source tree is loaded by bb via -cp, so these tests do not
   shell back into a `bb` task."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private project-root
  (or (System/getProperty "user.dir") "."))

(defmacro with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(defn- run-knot
  "Run `knot <args...>` with `cwd` as the working directory. Returns
   `{:exit n :out s :err s}`."
  [cwd & args]
  (let [main-clj (str (fs/path project-root "src" "knot" "main.clj"))]
    @(p/process (concat ["bb" "-cp" (str (fs/path project-root "src"))
                         "-e"
                         (str "(require '[knot.main]) "
                              "(apply (resolve 'knot.main/-main) *command-line-args*)")
                         "--"]
                        args)
                {:dir cwd :out :string :err :string})))

(deftest create-then-show-end-to-end-test
  (testing "create writes a slug-suffixed file in .tickets/, show reads it back"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "create" "Fix login bug")]
        (is (zero? exit) (str "create exited non-zero. err=" err))
        (let [path (str/trim out)]
          (is (str/includes? path ".tickets"))
          (is (fs/exists? path))
          (is (str/ends-with? path "--fix-login-bug.md"))
          (let [filename (str (fs/file-name path))
                id       (->> filename
                              (re-matches #"(.+)--fix-login-bug\.md")
                              second)
                shown    (run-knot tmp "show" id)]
            (is (zero? (:exit shown)) (str "show exited non-zero. err=" (:err shown)))
            (is (str/includes? (:out shown) (str "id: " id)))
            (is (str/includes? (:out shown) "# Fix login bug"))
            (testing "show preserves the file's frontmatter key order"
              (is (= (slurp path) (:out shown))))))))))

(deftest show-preserves-order-with-many-keys-test
  (testing "show output equals the on-disk file even with many frontmatter keys"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "Hello"
                      "-d" "Desc"
                      "-p" "1"
                      "-t" "bug"
                      "-a" "alice"
                      "--parent" "kno-other"
                      "--tags" "auth,p0"
                      "--external-ref" "JIRA-1"
                      "--external-ref" "JIRA-2")]
        (is (zero? exit) (str "create err=" err))
        (let [path (str/trim out)
              id   (->> (str (fs/file-name path))
                        (re-matches #"(.+)--hello\.md")
                        second)
              shown (run-knot tmp "show" id)]
          (is (zero? (:exit shown)))
          (is (= (slurp path) (:out shown))
              "show should reproduce the on-disk file exactly"))))))

(deftest create-from-subdirectory-test
  (testing "create works when invoked from any subdirectory of the project"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [sub (str (fs/path tmp "a" "b" "c"))]
        (fs/create-dirs sub)
        (let [{:keys [exit out]} (run-knot sub "create" "Walk up works")]
          (is (zero? exit))
          ;; the created file lives under the *project root's* .tickets, not
          ;; under the subdirectory we ran from
          (is (str/starts-with? (str/trim out) (str tmp "/.tickets/"))))))))

(deftest unknown-command-test
  (testing "unknown commands fail with non-zero exit and a stderr message"
    (with-tmp tmp
      (let [{:keys [exit err]} (run-knot tmp "frobnicate")]
        (is (= 1 exit))
        (is (str/includes? err "unknown command"))))))

(deftest show-missing-id-test
  (testing "show on a missing id exits non-zero with a stderr message"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit err]} (run-knot tmp "show" "no-such-id")]
        (is (= 1 exit))
        (is (str/includes? err "no ticket matching"))))))

(deftest ls-end-to-end-test
  (testing "ls renders a table with both ticket titles when piped"
    (with-tmp tmp
      (run-knot tmp "create" "First ticket")
      (run-knot tmp "create" "Second ticket")
      (let [{:keys [exit out err]} (run-knot tmp "ls")]
        (is (zero? exit) (str "ls err=" err))
        (is (str/includes? out "ID"))
        (is (str/includes? out "STATUS"))
        (is (str/includes? out "TITLE"))
        (is (str/includes? out "First ticket"))
        (is (str/includes? out "Second ticket"))
        (is (not (re-find #"\[" out))
            "no ANSI escape sequences should appear when stdout is piped"))))

  (testing "ls --json emits a bare JSON array of objects"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [exit out err]} (run-knot tmp "ls" "--json")]
        (is (zero? exit) (str "ls --json err=" err))
        (let [trimmed (str/trim out)]
          (is (str/starts-with? trimmed "["))
          (is (str/ends-with? trimmed "]"))
          (is (str/includes? trimmed "\"status\":\"open\"")))))))

(deftest show-json-end-to-end-test
  (testing "show --json emits a bare JSON object with snake_case keys"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello"
                                    "--external-ref" "JIRA-1")
            id   (->> (str (fs/file-name (str/trim out)))
                      (re-matches #"(.+)--hello\.md")
                      second)
            {:keys [exit out err]} (run-knot tmp "show" id "--json")]
        (is (zero? exit) (str "show --json err=" err))
        (let [trimmed (str/trim out)]
          (is (str/starts-with? trimmed "{"))
          (is (str/ends-with? trimmed "}"))
          (is (str/includes? trimmed (str "\"id\":\"" id "\"")))
          (is (str/includes? trimmed "\"external_refs\""))
          (is (str/includes? trimmed "\"body\"")))))))

(deftest show-json-preserves-key-order-test
  (testing "show --json keeps frontmatter keys in on-disk order even with > 8 keys"
    ;; Regression: `(into {} ordered-map)` silently rebuilds as a hash-map
    ;; once Clojure's array-map threshold (8 entries) is crossed, scrambling
    ;; the JSON key order. Drive a fully-populated ticket through `--json`
    ;; and assert the canonical key positions are strictly increasing.
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello"
                                    "-d" "Desc"
                                    "-p" "1"
                                    "-t" "bug"
                                    "-a" "alice"
                                    "--parent" "kno-other"
                                    "--tags" "auth,p0"
                                    "--external-ref" "JIRA-1")
            id (->> (str (fs/file-name (str/trim out)))
                    (re-matches #"(.+)--hello\.md")
                    second)
            {:keys [exit out err]} (run-knot tmp "show" id "--json")
            positions (mapv #(str/index-of out (str "\"" % "\""))
                            ["id" "status" "type" "priority" "mode"
                             "created" "updated" "assignee" "parent"
                             "tags" "external_refs"])]
        (is (zero? exit) (str "show --json err=" err))
        (is (every? some? positions)
            (str "expected all canonical keys present, got positions=" positions))
        (is (apply < positions)
            (str "JSON keys should appear in canonical on-disk order, got positions="
                 positions))))))

(deftest stderr-discipline-test
  (testing "create — data on stdout, stderr is empty on success"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "create" "Title")]
        (is (zero? exit))
        (is (not (str/blank? out)) "expected the created path on stdout")
        (is (str/blank? err) (str "expected stderr empty on success, got: " (pr-str err))))))

  (testing "show — data on stdout, stderr is empty on success"
    (with-tmp tmp
      (let [created (run-knot tmp "create" "T")
            id (->> (str (fs/file-name (str/trim (:out created))))
                    (re-matches #"(.+)--t\.md")
                    second)
            {:keys [exit out err]} (run-knot tmp "show" id)]
        (is (zero? exit))
        (is (not (str/blank? out)))
        (is (str/blank? err)))))

  (testing "ls — data on stdout, stderr is empty on success"
    (with-tmp tmp
      (run-knot tmp "create" "T1")
      (let [{:keys [exit out err]} (run-knot tmp "ls")]
        (is (zero? exit))
        (is (not (str/blank? out)))
        (is (str/blank? err)))))

  (testing "ls --json — data on stdout, stderr is empty on success"
    (with-tmp tmp
      (run-knot tmp "create" "T1")
      (let [{:keys [exit out err]} (run-knot tmp "ls" "--json")]
        (is (zero? exit))
        (is (not (str/blank? out)))
        (is (str/blank? err)))))

  (testing "show on a missing id — error on stderr, stdout is empty"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]} (run-knot tmp "show" "no-such-id")]
        (is (= 1 exit))
        (is (str/blank? out) (str "expected stdout empty on error, got: " (pr-str out)))
        (is (not (str/blank? err))))))

  (testing "unknown command — usage on stderr, stdout is empty"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "frobnicate")]
        (is (= 1 exit))
        (is (str/blank? out))
        (is (str/includes? err "unknown command"))))))

(deftest status-end-to-end-test
  (testing "status <id> <new-status> updates the ticket and is visible via show"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (->> (str (fs/file-name (str/trim out)))
                    (re-matches #"(.+)--hello\.md")
                    second)
            {:keys [exit err]} (run-knot tmp "status" id "in_progress")]
        (is (zero? exit) (str "status err=" err))
        (let [shown (run-knot tmp "show" id "--json")]
          (is (zero? (:exit shown)))
          (is (str/includes? (:out shown) "\"status\":\"in_progress\"")))))))

(deftest start-close-reopen-end-to-end-test
  (testing "start moves a ticket to in_progress; close archives; reopen restores"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            live-path (str/trim out)
            id        (->> (str (fs/file-name live-path))
                           (re-matches #"(.+)--hello\.md")
                           second)
            ;; start
            {:keys [exit err]} (run-knot tmp "start" id)
            _ (is (zero? exit) (str "start err=" err))
            shown1 (run-knot tmp "show" id "--json")
            _ (is (str/includes? (:out shown1) "\"status\":\"in_progress\""))
            ;; close — file should move into archive/
            {:keys [exit err]} (run-knot tmp "close" id)
            _ (is (zero? exit) (str "close err=" err))
            archive-path (str (fs/path tmp ".tickets" "archive"
                                       (fs/file-name live-path)))
            _ (is (fs/exists? archive-path)
                  (str "expected archived file at " archive-path))
            _ (is (not (fs/exists? live-path))
                  "live-directory file should be gone after close")
            shown2 (run-knot tmp "show" id "--json")
            _ (is (str/includes? (:out shown2) "\"status\":\"closed\""))
            _ (is (str/includes? (:out shown2) "\"closed\":")
                  "closed timestamp should be present after close")
            ;; reopen — file should move back to live
            {:keys [exit err]} (run-knot tmp "reopen" id)
            _ (is (zero? exit) (str "reopen err=" err))]
        (is (fs/exists? live-path) "reopened file should be back in live dir")
        (is (not (fs/exists? archive-path))
            "archive copy should be gone after reopen")
        (let [shown3 (run-knot tmp "show" id "--json")]
          (is (str/includes? (:out shown3) "\"status\":\"open\""))
          (is (not (str/includes? (:out shown3) "\"closed\":"))
              "reopen should clear :closed from frontmatter"))))))

(deftest status-missing-id-test
  (testing "status on a missing id exits non-zero with a stderr message"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit err]} (run-knot tmp "status" "no-such-id" "open")]
        (is (= 1 exit))
        (is (str/includes? err "no ticket matching"))))))

(deftest ls-default-hides-closed-test
  (testing "default ls excludes terminal-status tickets"
    (with-tmp tmp
      (run-knot tmp "create" "Live ticket")
      ;; Hand-write a closed ticket directly to disk to avoid depending on
      ;; a 'close' command (not yet implemented).
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "tmp-closedid001--closed-ticket.md"))
            (str "---\n"
                 "id: tmp-closedid001\n"
                 "status: closed\n"
                 "type: task\n"
                 "priority: 2\n"
                 "mode: hitl\n"
                 "---\n\n"
                 "# Closed ticket\n"))
      (let [{:keys [exit out err]} (run-knot tmp "ls")]
        (is (zero? exit) (str "ls err=" err))
        (is (str/includes? out "Live ticket"))
        (is (not (str/includes? out "Closed ticket"))
            "default ls should hide terminal-status tickets")))))

(deftest init-end-to-end-test
  (testing "knot init writes .knot.edn and creates the tickets dir"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "init")]
        (is (zero? exit) (str "init err=" err))
        (is (str/includes? (str/trim out) ".knot.edn"))
        (is (fs/exists? (fs/path tmp ".knot.edn")))
        (is (fs/directory? (fs/path tmp ".tickets"))))))

  (testing "knot init --tickets-dir tasks --prefix abc honors flags"
    (with-tmp tmp
      (let [{:keys [exit err]} (run-knot tmp "init"
                                         "--tickets-dir" "tasks"
                                         "--prefix" "abc")]
        (is (zero? exit) (str "init err=" err))
        (is (fs/directory? (fs/path tmp "tasks")))
        (let [content (slurp (str (fs/path tmp ".knot.edn")))]
          (is (str/includes? content ":tickets-dir \"tasks\""))
          (is (str/includes? content ":prefix \"abc\""))))))

  (testing "knot init aborts when .knot.edn already exists"
    (with-tmp tmp
      (run-knot tmp "init")
      (let [{:keys [exit err]} (run-knot tmp "init")]
        (is (= 1 exit))
        (is (str/includes? err "already exists")))))

  (testing "knot init --force overwrites an existing .knot.edn"
    (with-tmp tmp
      (run-knot tmp "init")
      ;; Hand-edit so we can detect the overwrite
      (spit (str (fs/path tmp ".knot.edn")) "{:default-type \"feature\"}")
      (let [{:keys [exit err]} (run-knot tmp "init" "--force")]
        (is (zero? exit) (str "init --force err=" err))
        (is (str/includes? (slurp (str (fs/path tmp ".knot.edn")))
                           ":default-priority")
            "stub should be back after --force")))))

(deftest config-flows-into-create-test
  (testing ".knot.edn :default-type and :default-priority show up in created tickets"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:default-type "feature"
                     :default-priority 0}))
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]} (run-knot tmp "create" "Configured")]
        (is (zero? exit) (str "create err=" err))
        (let [path  (str/trim out)
              text  (slurp path)]
          (is (str/includes? text "type: feature")
              "config :default-type should override the hardcoded default")
          (is (str/includes? text "priority: 0")
              "config :default-priority should override the hardcoded default")))))

  (testing "custom :tickets-dir from .knot.edn is honored on create"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:tickets-dir "tasks"}))
      (fs/create-dirs (fs/path tmp "tasks"))
      (let [{:keys [exit out err]} (run-knot tmp "create" "Routed")]
        (is (zero? exit) (str "create err=" err))
        (is (str/starts-with? (str/trim out) (str tmp "/tasks/"))
            "ticket should be written under the configured tickets-dir"))))

  (testing "invalid .knot.edn fails fast with a clear stderr error"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:default-priority "high"}))
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit err]} (run-knot tmp "create" "X")]
        (is (= 1 exit))
        (is (str/includes? err "default-priority"))))))

(defn- id-from-create-out
  "Extract the ticket id from a create-cmd's stdout path string of the
   form `<root>/.tickets/<id>--<slug>.md`."
  [out slug]
  (->> (str/trim out)
       fs/file-name
       str
       (re-matches (re-pattern (str "(.+)--" slug "\\.md")))
       second))

(deftest dep-undep-end-to-end-test
  (testing "dep adds to :deps, undep removes; cycle add is rejected with stderr"
    (with-tmp tmp
      (let [a-out (run-knot tmp "create" "Alpha")
            b-out (run-knot tmp "create" "Beta")
            a-id  (id-from-create-out (:out a-out) "alpha")
            b-id  (id-from-create-out (:out b-out) "beta")
            ;; happy path: a depends on b
            dep1  (run-knot tmp "dep" a-id b-id)
            shown (run-knot tmp "show" a-id)]
        (is (zero? (:exit dep1)) (str "dep err=" (:err dep1)))
        (is (str/includes? (:out shown) (str "- " b-id))
            "show output should list b under :deps")

        ;; cycle: b depends on a → reject
        (let [{:keys [exit err]} (run-knot tmp "dep" b-id a-id)]
          (is (= 1 exit))
          (is (str/includes? err "cycle"))
          (is (str/includes? err a-id))
          (is (str/includes? err b-id)))

        ;; the rejected edge must not have been written
        (let [shown-b (run-knot tmp "show" b-id)]
          (is (not (str/includes? (:out shown-b) (str "- " a-id)))
              "rejected dep must not be persisted"))

        ;; undep removes the edge
        (let [{:keys [exit]} (run-knot tmp "undep" a-id b-id)
              shown-a (run-knot tmp "show" a-id)]
          (is (zero? exit))
          (is (not (str/includes? (:out shown-a) (str "- " b-id)))
              "undep should drop the entry"))))))

(deftest dep-tree-end-to-end-test
  (testing "dep tree renders ASCII; --json emits a nested object"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta")) "beta")
            _    (run-knot tmp "dep" a-id b-id)
            text (run-knot tmp "dep" "tree" a-id)
            json (run-knot tmp "dep" "tree" a-id "--json")]
        (is (zero? (:exit text)))
        (is (str/includes? (:out text) "└──"))
        (is (str/includes? (:out text) "Alpha"))
        (is (str/includes? (:out text) "Beta"))
        (is (zero? (:exit json)))
        (is (str/starts-with? (str/trim (:out json)) "{"))
        (is (str/includes? (:out json) (str "\"id\":\"" a-id "\"")))
        (is (str/includes? (:out json) (str "\"id\":\"" b-id "\"")))))))

(deftest dep-cycle-end-to-end-test
  (testing "dep cycle exits 0 with no cycles"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta")) "beta")
            _    (run-knot tmp "dep" a-id b-id)
            {:keys [exit]} (run-knot tmp "dep" "cycle")]
        (is (zero? exit) "no cycles → exit 0")))))

(deftest ready-blocked-end-to-end-test
  (testing "ready surfaces unblocked tickets; blocked surfaces tickets with open deps"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha task")) "alpha-task")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta task")) "beta-task")
            _    (run-knot tmp "dep" a-id b-id)
            ready   (run-knot tmp "ready")
            blocked (run-knot tmp "blocked")]
        (is (zero? (:exit ready)))
        (is (str/includes? (:out ready) "Beta task")
            "Beta has no deps → ready")
        (is (not (str/includes? (:out ready) "Alpha task"))
            "Alpha is blocked by Beta → not ready")
        (is (zero? (:exit blocked)))
        (is (str/includes? (:out blocked) "Alpha task"))
        (is (not (str/includes? (:out blocked) "Beta task")))))))

(deftest broken-ref-warning-end-to-end-test
  (testing "show on a ticket with a broken :deps ref emits a stderr warning, exits 0"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            _    (run-knot tmp "dep" a-id "kno-ghost")
            {:keys [exit out err]} (run-knot tmp "show" a-id)]
        (is (zero? exit))
        (is (str/includes? out "kno-ghost") "raw :deps still rendered")
        (is (str/includes? err "kno-ghost"))
        (is (str/includes? err "missing"))))))
