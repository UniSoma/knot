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
