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
