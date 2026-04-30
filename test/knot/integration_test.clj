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
   `{:exit n :out s :err s}`. Stdin is closed (empty) by default so that
   commands which probe stdin (`add-note` without a text arg) do not
   block waiting for the parent's tty."
  [cwd & args]
  (let [main-clj (str (fs/path project-root "src" "knot" "main.clj"))]
    @(p/process (concat ["bb" "-cp" (str (fs/path project-root "src"))
                         "-e"
                         (str "(require '[knot.main]) "
                              "(apply (resolve 'knot.main/-main) *command-line-args*)")
                         "--"]
                        args)
                {:dir cwd :in "" :out :string :err :string})))

(defn- run-knot-with-stdin
  "Like `run-knot`, but pipes `stdin-str` into the subprocess's stdin
   instead of closing it. Use to exercise commands that read piped input
   (`add-note` with no text arg)."
  [cwd stdin-str & args]
  @(p/process (concat ["bb" "-cp" (str (fs/path project-root "src"))
                       "-e"
                       (str "(require '[knot.main]) "
                            "(apply (resolve 'knot.main/-main) *command-line-args*)")
                       "--"]
                      args)
              {:dir cwd :in stdin-str :out :string :err :string}))

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
            (is (str/includes? (:out shown) "title: Fix login bug"))
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
          ;; under the subdirectory we ran from. Canonicalize tmp because
          ;; macOS symlinks /var → /private/var; knot's output is canonical.
          (is (str/starts-with? (str/trim out)
                                (str (fs/canonicalize tmp) "/.tickets/"))))))))

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

(deftest list-end-to-end-test
  (testing "knot list renders the same table as knot ls"
    (with-tmp tmp
      (run-knot tmp "create" "First ticket")
      (run-knot tmp "create" "Second ticket")
      (let [{:keys [exit out err]} (run-knot tmp "list")]
        (is (zero? exit) (str "list err=" err))
        (is (str/includes? out "ID"))
        (is (str/includes? out "STATUS"))
        (is (str/includes? out "TITLE"))
        (is (str/includes? out "First ticket"))
        (is (str/includes? out "Second ticket")))))

  (testing "knot list --json emits the same JSON shape as knot ls --json"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [exit out err]} (run-knot tmp "list" "--json")]
        (is (zero? exit) (str "list --json err=" err))
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

(deftest create-mode-flags-end-to-end-test
  (testing "--afk is sugar for --mode afk"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "create" "Afk job" "--afk")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "mode: afk"))))))

  (testing "--hitl is sugar for --mode hitl"
    (with-tmp tmp
      ;; Use a config with default-mode "afk" so --hitl actually changes
      ;; the result and can't pass by accident on the global default.
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:default-mode "afk"}))
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]} (run-knot tmp "create" "Hitl job" "--hitl")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "mode: hitl"))))))

  (testing "explicit --mode wins over inferred --afk/--hitl shortcut"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "Mixed" "--afk" "--mode" "hitl")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "mode: hitl")
              "explicit --mode hitl should override --afk shortcut"))))))

(deftest create-body-flags-with-dash-prefixed-values-end-to-end-test
  (testing "--acceptance with a dash-prefixed bullet value writes the body verbatim"
    ;; Regression: babashka.cli mis-parsed body-section flag values whose
    ;; first token began with `-`, treating each whitespace-split fragment
    ;; as its own flag and (depending on neighbouring flags) either crashing
    ;; or silently writing garbage. `--description / --design / --acceptance`
    ;; must consume their next argv slot verbatim.
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "T"
                      "--acceptance" "- [ ] some item")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "## Acceptance Criteria"))
          (is (str/includes? text "- [ ] some item"))))))

  (testing "--acceptance=<value> form also accepts dash-prefixed values"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "T"
                      "--acceptance=- [ ] some item")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "## Acceptance Criteria"))
          (is (str/includes? text "- [ ] some item"))))))

  (testing "--description and --design accept dash-prefixed values"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "T"
                      "--description" "- desc bullet"
                      "--design"      "- design bullet")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "## Description"))
          (is (str/includes? text "- desc bullet"))
          (is (str/includes? text "## Design"))
          (is (str/includes? text "- design bullet"))))))

  (testing "ordinary flags still parse identically when mixed with body flags"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "T"
                      "--priority"  "1"
                      "--type"      "bug"
                      "--tags"      "auth,p0"
                      "--acceptance" "- [ ] item")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "priority: 1"))
          (is (str/includes? text "type: bug"))
          (is (str/includes? text "- auth"))
          (is (str/includes? text "- p0"))
          (is (str/includes? text "- [ ] item")))))))

(deftest mode-missing-on-legacy-tickets-test
  (testing "tickets without :mode load and behave leniently"
    ;; Legacy tickets predating the :mode field must continue to work:
    ;; show/ls/ready treat them as 'unfiltered' rather than rewriting or
    ;; rejecting them. A future change adding `(or mode default)` somewhere
    ;; on the load path would silently mutate these files; this test
    ;; locks in lenient handling.
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      ;; Hand-write a ticket with no :mode key in frontmatter.
      (spit (str (fs/path tmp ".tickets" "tmp-noModeId01--no-mode.md"))
            (str "---\n"
                 "id: tmp-noModeId01\n"
                 "title: No mode\n"
                 "status: open\n"
                 "type: task\n"
                 "priority: 2\n"
                 "---\n"))
      (testing "show works and does not invent a :mode line"
        (let [{:keys [exit out]} (run-knot tmp "show" "tmp-noModeId01")]
          (is (zero? exit))
          (is (str/includes? out "title: No mode"))
          (is (not (re-find #"(?m)^mode: " out))
              "show output must not synthesize a mode line absent from disk")))
      (testing "ls includes the mode-less ticket by default"
        (let [{:keys [exit out]} (run-knot tmp "ls")]
          (is (zero? exit))
          (is (str/includes? out "No mode"))))
      (testing "ls --mode afk excludes the mode-less ticket"
        (let [{:keys [exit out]} (run-knot tmp "ls" "--mode" "afk")]
          (is (zero? exit))
          (is (not (str/includes? out "No mode"))
              "explicit --mode afk filter must not match a ticket with no :mode")))
      (testing "ready includes the mode-less ticket (no deps, non-terminal)"
        (let [{:keys [exit out]} (run-knot tmp "ready")]
          (is (zero? exit))
          (is (str/includes? out "No mode"))))
      (testing "the on-disk file is unchanged after these reads"
        (let [path (fs/path tmp ".tickets" "tmp-noModeId01--no-mode.md")]
          (is (not (str/includes? (slurp (str path)) "mode:"))
              "no mode key should have been written to the file"))))))

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
        (is (str/starts-with? (str/trim out)
                              (str (fs/canonicalize tmp) "/tasks/"))
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
          (is (not (str/includes? (:out shown-b) "## Blockers"))
              "rejected dep must not be persisted (b has no :deps)")
          (is (not (str/includes? (:out shown-b) "deps:"))
              "b's frontmatter must not contain a :deps key"))

        ;; undep removes the edge
        (let [{:keys [exit]} (run-knot tmp "undep" a-id b-id)
              shown-a (run-knot tmp "show" a-id)]
          (is (zero? exit))
          (is (not (str/includes? (:out shown-a) "## Blockers"))
              "undep should drop the only :deps entry"))))))

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
        (is (zero? exit) "no cycles → exit 0"))))

  (testing "dep cycle on a hand-edited cycle: exit 1, stderr names both ids with arrows"
    ;; The CLI rejects cycle-creating `dep` adds, so the only way to land
    ;; one is to bypass it by editing the file directly.
    (with-tmp tmp
      (let [a-out  (run-knot tmp "create" "Alpha")
            b-out  (run-knot tmp "create" "Beta")
            a-id   (id-from-create-out (:out a-out) "alpha")
            b-id   (id-from-create-out (:out b-out) "beta")
            _      (run-knot tmp "dep" a-id b-id)
            b-path (str/trim (:out b-out))
            ;; inject `deps: [a-id]` immediately after Beta's opening fence
            [before after] (str/split (slurp b-path) #"---\n" 2)
            _ (spit b-path (str before "---\ndeps:\n  - " a-id "\n" after))
            {:keys [exit out err]} (run-knot tmp "dep" "cycle")]
        (is (= 1 exit) "cycle present → exit 1")
        (is (str/blank? out) "no data on stdout for cycle scan")
        (is (str/includes? err "knot dep cycle:") "stderr framing prefix")
        (is (str/includes? err "→") "stderr uses arrow joiner")
        (is (str/includes? err a-id))
        (is (str/includes? err b-id))))))

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

(deftest ls-filter-flags-end-to-end-test
  (testing "ls --mode afk filters out hitl tickets"
    (with-tmp tmp
      (run-knot tmp "create" "Afk job"  "--afk")
      (run-knot tmp "create" "Hitl job" "--hitl")
      (let [{:keys [exit out err]} (run-knot tmp "ls" "--mode" "afk")]
        (is (zero? exit) (str "ls err=" err))
        (is (str/includes? out "Afk job"))
        (is (not (str/includes? out "Hitl job"))))))

  (testing "ls --status open --mode afk ANDs the filters"
    (with-tmp tmp
      (run-knot tmp "create" "Afk open"  "--afk")
      (run-knot tmp "create" "Hitl open" "--hitl")
      (let [{:keys [exit out err]} (run-knot tmp "ls" "--status" "open"
                                              "--mode" "afk")]
        (is (zero? exit) (str "ls err=" err))
        (is (str/includes? out "Afk open"))
        (is (not (str/includes? out "Hitl open"))))))

  (testing "ls --type bug filters by type"
    (with-tmp tmp
      (run-knot tmp "create" "A bug"     "-t" "bug")
      (run-knot tmp "create" "A feature" "-t" "feature")
      (let [{:keys [out]} (run-knot tmp "ls" "--type" "bug")]
        (is (str/includes? out "A bug"))
        (is (not (str/includes? out "A feature"))))))

  (testing "ls --status closed surfaces archived tickets"
    (with-tmp tmp
      (run-knot tmp "create" "Live one")
      (let [created (run-knot tmp "create" "Will close")
            id      (id-from-create-out (:out created) "will-close")
            _       (run-knot tmp "close" id)
            {:keys [out]} (run-knot tmp "ls" "--status" "closed")]
        (is (str/includes? out "Will close"))
        (is (not (str/includes? out "Live one")))))))

(deftest ready-mode-filter-end-to-end-test
  (testing "ready --mode afk hides hitl tickets even when ready"
    (with-tmp tmp
      (run-knot tmp "create" "Afk job"  "--afk")
      (run-knot tmp "create" "Hitl job" "--hitl")
      (let [{:keys [exit out err]} (run-knot tmp "ready" "--mode" "afk")]
        (is (zero? exit) (str "ready err=" err))
        (is (str/includes? out "Afk job"))
        (is (not (str/includes? out "Hitl job"))))))

  (testing "ready --mode afk --limit caps after filtering"
    (with-tmp tmp
      (run-knot tmp "create" "Hitl one"   "--hitl")
      (run-knot tmp "create" "Hitl two"   "--hitl")
      (run-knot tmp "create" "Afk one"    "--afk")
      (run-knot tmp "create" "Afk two"    "--afk")
      (run-knot tmp "create" "Afk three"  "--afk")
      (let [{:keys [exit out]} (run-knot tmp "ready" "--mode" "afk"
                                         "--limit" "2")
            afk-hits  (count (re-seq #"Afk " out))
            hitl-hits (count (re-seq #"Hitl " out))]
        (is (zero? exit))
        (is (= 2 afk-hits))
        (is (zero? hitl-hits))))))

(deftest closed-end-to-end-test
  (testing "closed lists terminal tickets, closed --limit caps to newest"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta"))  "beta")
            c-id (id-from-create-out (:out (run-knot tmp "create" "Gamma")) "gamma")
            _    (run-knot tmp "close" a-id)
            ;; Sleep a real wall-clock millisecond between closes — store/save!
            ;; uses Instant/now when :now is not threaded in. The integration
            ;; harness invokes a fresh JVM per command, so this is the only way
            ;; to differentiate :closed timestamps.
            _    (Thread/sleep 5)
            _    (run-knot tmp "close" b-id)
            _    (Thread/sleep 5)
            _    (run-knot tmp "close" c-id)
            {full :out :as full-result} (run-knot tmp "closed")
            {capped :out} (run-knot tmp "closed" "--limit" "2")]
        (is (zero? (:exit full-result)))
        (is (str/includes? full "Alpha"))
        (is (str/includes? full "Beta"))
        (is (str/includes? full "Gamma"))
        ;; --limit 2 keeps the two most-recently closed (Beta, Gamma)
        (is (str/includes? capped "Gamma"))
        (is (str/includes? capped "Beta"))
        (is (not (str/includes? capped "Alpha"))))))

  (testing "closed --json returns a bare JSON array"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            _    (run-knot tmp "close" a-id)
            {:keys [out]} (run-knot tmp "closed" "--json")]
        (is (str/starts-with? (str/trim out) "["))
        (is (str/includes? out "\"status\":\"closed\"")))))

  (testing "closed and blocked reject filter flags they do not implement"
    ;; closed/blocked share the narrow list-spec (no filter keys). A user
    ;; who types `--mode afk` should get a clear failure, not a silent
    ;; no-op that returns the unfiltered list.
    (with-tmp tmp
      (doseq [cmd ["closed" "blocked"]
              flag ["--mode" "--status" "--assignee" "--tag" "--type"]]
        (let [{:keys [exit err]} (run-knot tmp cmd flag "x")]
          (is (= 1 exit)
              (str cmd " " flag " should exit non-zero"))
          (is (str/includes? err "Unknown option")
              (str cmd " " flag " should name the unknown option in stderr")))))))

(deftest link-unlink-end-to-end-test
  (testing "link writes symmetric :links to both files"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta")) "beta")
            {:keys [exit err]} (run-knot tmp "link" a-id b-id)]
        (is (zero? exit) (str "link err=" err))
        ;; show on either ticket should now expose the symmetric Linked section
        (let [{a-out :out} (run-knot tmp "show" a-id)
              {b-out :out} (run-knot tmp "show" b-id)]
          (is (str/includes? a-out "## Linked"))
          (is (str/includes? a-out (str "- " b-id "  Beta")))
          (is (str/includes? b-out "## Linked"))
          (is (str/includes? b-out (str "- " a-id "  Alpha")))))))

  (testing "link a b c creates all three pairs in one shot"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta")) "beta")
            c-id (id-from-create-out (:out (run-knot tmp "create" "Gamma")) "gamma")
            {:keys [exit]} (run-knot tmp "link" a-id b-id c-id)]
        (is (zero? exit))
        (let [{a-out :out} (run-knot tmp "show" a-id "--json")
              {b-out :out} (run-knot tmp "show" b-id "--json")
              {c-out :out} (run-knot tmp "show" c-id "--json")]
          (is (str/includes? a-out (str "\"id\":\"" b-id "\"")))
          (is (str/includes? a-out (str "\"id\":\"" c-id "\"")))
          (is (str/includes? b-out (str "\"id\":\"" a-id "\"")))
          (is (str/includes? b-out (str "\"id\":\"" c-id "\"")))
          (is (str/includes? c-out (str "\"id\":\"" a-id "\"")))
          (is (str/includes? c-out (str "\"id\":\"" b-id "\"")))))))

  (testing "unlink removes the link from both files"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta")) "beta")
            _    (run-knot tmp "link"   a-id b-id)
            {:keys [exit]} (run-knot tmp "unlink" a-id b-id)
            {a-out :out} (run-knot tmp "show" a-id)
            {b-out :out} (run-knot tmp "show" b-id)]
        (is (zero? exit))
        (is (not (str/includes? a-out "## Linked")))
        (is (not (str/includes? b-out "## Linked"))))))

  (testing "link too few args fails with a clear stderr message"
    (with-tmp tmp
      (let [_ (run-knot tmp "create" "Alpha")
            {:keys [exit err]} (run-knot tmp "link" "kno-only-one")]
        (is (= 1 exit))
        (is (str/includes? err "two or more")))))

  (testing "links survive archive transitions (closing one ticket leaves the other's link intact)"
    ;; AC: links survive archive transitions because :links references IDs not paths
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Beta")) "beta")
            _    (run-knot tmp "link"  a-id b-id)
            ;; close A — its file moves to .tickets/archive/
            {close-exit :exit} (run-knot tmp "close" a-id)
            archived-a (str (fs/path tmp ".tickets" "archive"))]
        (is (zero? close-exit))
        (is (some #(str/starts-with? (str (fs/file-name %)) a-id)
                  (when (fs/directory? archived-a) (fs/list-dir archived-a)))
            "A's file should now be in archive")
        ;; B is still live — its :links reference to A must still resolve via load-all
        (let [{b-out :out} (run-knot tmp "show" b-id)
              {b-json :out} (run-knot tmp "show" b-id "--json")]
          (is (str/includes? b-out "## Linked")
              "B's Linked section still computed even though A is archived")
          (is (str/includes? b-out (str "- " a-id "  Alpha"))
              "B's Linked entry resolves the archived A's title")
          (is (str/includes? b-json (str "\"id\":\"" a-id "\""))
              "B's --json linked array still names the archived A"))))))

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

(deftest add-note-end-to-end-test
  (testing "add-note <id> <text> appends a timestamped note under ## Notes"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            {:keys [exit out err]}
            (run-knot tmp "add-note" a-id "first note")]
        (is (zero? exit) (str "add-note err=" err))
        (is (str/includes? (str/trim out) "--alpha.md")
            "stdout is the saved path")
        (let [{:keys [out]} (run-knot tmp "show" a-id)]
          (is (str/includes? out "## Notes"))
          (is (str/includes? out "first note"))))))

  (testing "add-note <id> with no text and no piped stdin shows nothing weird"
    ;; This test documents the non-TTY path: when stdin is closed (default
    ;; for p/process) and no text arg, add-note tries to read stdin (gets
    ;; EOF / empty string), treats as cancel, and exits 0.
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            {:keys [exit]} (run-knot tmp "add-note" a-id)]
        (is (zero? exit))
        (let [{:keys [out]} (run-knot tmp "show" a-id)]
          (is (not (str/includes? out "## Notes"))
              "blank stdin should leave the file unchanged")))))

  (testing "add-note <id> reads piped stdin when no text arg is supplied"
    ;; Pins the real (slurp *in*) wiring in knot.main: with content piped
    ;; on stdin and no text arg, the body of the note is whatever was
    ;; piped in. Companion to the unit-level :stdin-reader-fn tests.
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            {:keys [exit out err]}
            (run-knot-with-stdin tmp "piped note body" "add-note" a-id)]
        (is (zero? exit) (str "add-note err=" err))
        (is (str/includes? (str/trim out) "--alpha.md"))
        (let [{:keys [out]} (run-knot tmp "show" a-id)]
          (is (str/includes? out "## Notes"))
          (is (str/includes? out "piped note body"))))))

  (testing "add-note on a missing id exits non-zero with a stderr message"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit err]} (run-knot tmp "add-note" "kno-nope" "x")]
        (is (= 1 exit))
        (is (str/includes? err "no ticket matching"))))))

(deftest close-with-summary-end-to-end-test
  (testing "close <id> --summary appends a closure note and archives"
    (with-tmp tmp
      (let [a-id     (id-from-create-out (:out (run-knot tmp "create" "Alpha"))
                                         "alpha")
            {:keys [exit out err]}
            (run-knot tmp "close" a-id "--summary" "shipped to prod")]
        (is (zero? exit) (str "close err=" err))
        (is (str/includes? (str/trim out) "/archive/")
            "closed file moved to archive")
        (let [{:keys [out]} (run-knot tmp "show" a-id)]
          (is (str/includes? out "status: closed"))
          (is (str/includes? out "## Notes"))
          (is (str/includes? out "shipped to prod"))))))

  (testing "status <id> closed --summary appends a closure note"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha"))
                                     "alpha")
            {:keys [exit err]}
            (run-knot tmp "status" a-id "closed" "--summary" "completed")]
        (is (zero? exit) (str "status err=" err))
        (let [{:keys [out]} (run-knot tmp "show" a-id)]
          (is (str/includes? out "completed")))))))

(deftest summary-rejected-on-non-terminal-end-to-end-test
  (testing "status <id> <non-terminal> --summary exits non-zero with stderr message"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha"))
                                     "alpha")
            {:keys [exit err]}
            (run-knot tmp "status" a-id "in_progress" "--summary" "nope")]
        (is (= 1 exit))
        (is (str/includes? err "summary")))))

  (testing "start <id> --summary errors"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha"))
                                     "alpha")
            {:keys [exit err]} (run-knot tmp "start" a-id
                                         "--summary" "nope")]
        (is (= 1 exit))
        (is (str/includes? err "summary")))))

  (testing "reopen <id> --summary errors"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha"))
                                     "alpha")
            _    (run-knot tmp "close" a-id)
            {:keys [exit err]} (run-knot tmp "reopen" a-id
                                         "--summary" "nope")]
        (is (= 1 exit))
        (is (str/includes? err "summary"))))))

(deftest prime-end-to-end-test
  (testing "prime in a populated project exits 0 and emits Project + Ready + Commands"
    (with-tmp tmp
      (run-knot tmp "create" "Live ticket")
      (let [{:keys [exit out err]} (run-knot tmp "prime")]
        (is (zero? exit) (str "prime err=" err))
        (is (str/includes? out "## Project"))
        (is (str/includes? out "## Ready"))
        (is (str/includes? out "## Commands"))
        (is (not (str/includes? out "## Schema"))
            "schema cheatsheet is retired")
        (is (not (str/includes? out "## In Progress"))
            "open-but-not-started ticket should not trigger an In Progress section")
        (is (str/includes? out "Live ticket")))))

  (testing "prime --json emits a bare object with the documented snake_case keys"
    (with-tmp tmp
      (run-knot tmp "create" "Live ticket")
      (let [{:keys [exit out err]} (run-knot tmp "prime" "--json")]
        (is (zero? exit) (str "prime --json err=" err))
        (is (str/starts-with? (str/trim out) "{"))
        (is (str/includes? out "\"project\""))
        (is (str/includes? out "\"in_progress\""))
        (is (str/includes? out "\"ready\""))
        (is (str/includes? out "\"ready_truncated\""))
        (is (str/includes? out "\"ready_remaining\""))
        (is (not (str/includes? out "## Schema"))
            "JSON output drops the schema cheatsheet"))))

  (testing "prime in a directory with no Knot project still exits 0 and points at `knot init`"
    ;; Bound the walk-up by creating a temp dir under a parent we can mark
    ;; with a marker — but actually the simplest safe variant is to use
    ;; the temp dir itself: babashka.fs/create-temp-dir returns a path
    ;; under /tmp which has no `.knot.edn` or `.tickets/` ancestor.
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "prime")]
        (is (zero? exit) (str "prime in non-project dir err=" err))
        (is (str/includes? out "knot init")
            "preamble points the user at `knot init`"))))

  (testing "prime in an empty Knot project exits 0 with Project + Ready headings (no In Progress)"
    (with-tmp tmp
      (run-knot tmp "init")
      (let [{:keys [exit out err]} (run-knot tmp "prime")]
        (is (zero? exit) (str "prime in empty project err=" err))
        (is (str/includes? out "## Project"))
        (is (str/includes? out "## Ready"))
        (is (not (str/includes? out "## In Progress"))
            "empty project has no in-progress tickets — heading is suppressed"))))

  (testing "prime in an archive-only project exits 0 with empty ready section and no In Progress"
    (with-tmp tmp
      (let [a-out (run-knot tmp "create" "Will close")
            a-id  (id-from-create-out (:out a-out) "will-close")
            _     (run-knot tmp "close" a-id)
            {:keys [exit out err]} (run-knot tmp "prime")]
        (is (zero? exit) (str "prime in archive-only project err=" err))
        (is (re-find #"archive: 1" out))
        (is (not (str/includes? out "## In Progress"))
            "no in-progress tickets → no In Progress heading")
        (let [rd-start (str/index-of out "## Ready")
              ;; Closed tickets surface under Recently Closed, so the
              ;; Ready slice ends at that heading (or Commands if absent).
              rd-end   (or (str/index-of out "## Recently Closed")
                           (str/index-of out "## Commands"))
              rd-section (subs out rd-start rd-end)]
          (is (not (str/includes? rd-section "Will close"))
              "closed ticket does not appear in ready")))))

  (testing "prime --mode afk filters the ready section to afk-only tickets"
    (with-tmp tmp
      (run-knot tmp "create" "Afk job"  "--afk")
      (run-knot tmp "create" "Hitl job" "--hitl")
      (let [{:keys [exit out err]} (run-knot tmp "prime" "--mode" "afk")]
        (is (zero? exit) (str "prime --mode afk err=" err))
        (let [rd-start (str/index-of out "## Ready")
              cm-start (str/index-of out "## Commands")
              section  (subs out rd-start cm-start)]
          (is (str/includes? section "Afk job"))
          (is (not (str/includes? section "Hitl job")))))))

  (testing "prime --limit overrides the default cap and emits a truncation footer"
    (with-tmp tmp
      (run-knot tmp "create" "T1")
      (run-knot tmp "create" "T2")
      (run-knot tmp "create" "T3")
      (let [{:keys [exit out err]} (run-knot tmp "prime" "--limit" "1")]
        (is (zero? exit) (str "prime --limit err=" err))
        (is (str/includes? out "+2 more"))
        (is (str/includes? out "knot ready")))))

  (testing "prime opens with a directive line and surfaces the user-says mapping"
    (with-tmp tmp
      (run-knot tmp "create" "Live ticket")
      (let [{:keys [exit out err]} (run-knot tmp "prime")
            first-line (->> (str/split-lines out)
                            (remove str/blank?)
                            first)]
        (is (zero? exit) (str "prime err=" err))
        (is (some? first-line) "prime emitted a non-blank line")
        (is (re-find #"(?i)^use\b.*\bknot\b" first-line)
            "first non-blank line is a directive about using `knot`")
        (is (not (re-find #"^You are working in" first-line))
            "first line is no longer the legacy descriptive preamble")
        (is (str/includes? out "what's next")
            "user-phrase mapping surfaces 'what's next'")
        (is (str/includes? out "tackle")
            "user-phrase mapping surfaces 'let's tackle <id>'")
        (is (str/includes? out "blocked on")
            "user-phrase mapping surfaces 'blocked on'")
        (is (re-find #"(?i)don't|do not" out)
            "negative-space directive present")
        (is (str/includes? out ".tickets")
            "negative-space directive references .tickets/"))))

  (testing "prime --json shape unchanged: no directive prose key, snake_case envelope preserved"
    (with-tmp tmp
      (run-knot tmp "create" "Live ticket")
      (let [{:keys [exit out err]} (run-knot tmp "prime" "--json")]
        (is (zero? exit) (str "prime --json err=" err))
        (is (str/starts-with? (str/trim out) "{"))
        (is (str/includes? out "\"project\""))
        (is (str/includes? out "\"in_progress\""))
        (is (str/includes? out "\"ready\""))
        (is (str/includes? out "\"ready_truncated\""))
        (is (str/includes? out "\"ready_remaining\""))
        (is (not (re-find #"(?i)you are working in" out))
            "JSON output carries no descriptive preamble")
        (is (not (str/includes? out "what's next"))
            "JSON output does not embed the user-says mapping prose"))))

  (testing "show resolves a unique partial id (post-prefix ULID portion)"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Partial id")
            full-id  (id-from-create-out out "partial-id")
            ;; first 6 chars of the ULID suffix — a generated id has 12, so
            ;; 6 is statistically unique in a single-ticket project
            suffix   (subs full-id (inc (str/index-of full-id "-")))
            partial  (subs suffix 0 6)
            shown    (run-knot tmp "show" partial)]
        (is (zero? (:exit shown)) (str "show <partial> err=" (:err shown)))
        (is (str/includes? (:out shown) (str "id: " full-id))))))

  (testing "show fails with a candidate list when the partial id is ambiguous"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01abc111111--a.md"))
            "---\nid: kno-01abc111111\nstatus: open\n---\n\n# A\n")
      (spit (str (fs/path tmp ".tickets" "kno-01abc222222--b.md"))
            "---\nid: kno-01abc222222\nstatus: open\n---\n\n# B\n")
      (let [{:keys [exit err]} (run-knot tmp "show" "kno-01abc")]
        (is (= 1 exit))
        (is (str/includes? err "ambiguous"))
        (is (str/includes? err "kno-01abc111111"))
        (is (str/includes? err "kno-01abc222222")))))

  (testing "knot init does NOT modify .claude/settings.json"
    ;; Ensure a global SessionStart hook can be wired from the README
    ;; without `knot init` clobbering it. The init command writes only
    ;; .knot.edn and the tickets directory.
    (with-tmp tmp
      (let [claude-dir (fs/path tmp ".claude")
            settings   (fs/path claude-dir "settings.json")
            _ (fs/create-dirs claude-dir)
            _ (spit (str settings) "{\"hooks\":{\"SessionStart\":\"existing\"}}")
            before (slurp (str settings))
            {:keys [exit err]} (run-knot tmp "init")]
        (is (zero? exit) (str "init err=" err))
        (is (= before (slurp (str settings)))
            "init must not touch .claude/settings.json")))))
