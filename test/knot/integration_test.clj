(ns knot.integration-test
  "End-to-end tests that drive the real CLI via babashka.process. The
   project's source tree is loaded by bb via -cp, so these tests do not
   shell back into a `bb` task."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.ticket :as ticket]))

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

(deftest check-end-to-end-test
  (testing "check on a clean project: exit 0, ok footer on stdout"
    (with-tmp tmp
      (run-knot tmp "create" "Alpha")
      (let [{:keys [exit out err]} (run-knot tmp "check")]
        (is (zero? exit) (str "check err=" err))
        (is (str/blank? err))
        (is (str/includes? out "ok")))))

  (testing "check --json on a clean project: ok:true envelope, exit 0"
    (with-tmp tmp
      (run-knot tmp "create" "Alpha")
      (let [{:keys [exit out err]} (run-knot tmp "check" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (str/blank? err))
        (is (true? (:ok parsed)))
        (is (= [] (get-in parsed [:data :issues])))
        (is (map? (get-in parsed [:data :scanned]))))))

  (testing "check exit 2 when run outside a project (cannot scan)"
    (with-tmp tmp
      ;; tmp has no .knot.edn and no .tickets/ — nothing to scan
      (let [{:keys [exit out err]} (run-knot tmp "check")]
        (is (= 2 exit))
        (is (str/blank? out))
        (is (str/includes? err "knot check:")))))

  (testing "check --json exit 2 emits an error envelope on stdout"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "check" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 2 exit))
        (is (str/blank? err))
        (is (false? (:ok parsed)))
        (is (some? (get-in parsed [:error :code])))
        (is (not (contains? parsed :data))
            "cannot-scan envelope omits :data")))))

(deftest show-missing-id-test
  (testing "show on a missing id exits non-zero with a stderr message"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit err]} (run-knot tmp "show" "no-such-id")]
        (is (= 1 exit))
        (is (str/includes? err "no ticket matching"))))))

(deftest read-cmd-error-envelope-test
  (testing "show --json on a missing id emits the v0.3 error envelope on stdout (exit 1)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]} (run-knot tmp "show" "no-such-id" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err)
            "error envelope goes to stdout, not stderr — JSON consumers parse stdout")
        (is (= 1 (:schema_version parsed)))
        (is (= false (:ok parsed)))
        (is (= "not_found" (get-in parsed [:error :code])))
        (is (str/includes? (get-in parsed [:error :message]) "no-such-id"))
        (is (not (contains? parsed :data))
            "error envelope does not carry a :data key"))))

  (testing "show --json on an ambiguous partial id emits ambiguous_id error envelope"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01abc111111--a.md"))
            "---\nid: kno-01abc111111\nstatus: open\n---\n\n# A\n")
      (spit (str (fs/path tmp ".tickets" "kno-01abc222222--b.md"))
            "---\nid: kno-01abc222222\nstatus: open\n---\n\n# B\n")
      (let [{:keys [exit out err]} (run-knot tmp "show" "kno-01abc" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err))
        (is (= 1 (:schema_version parsed)))
        (is (= false (:ok parsed)))
        (is (= "ambiguous_id" (get-in parsed [:error :code])))
        (is (= ["kno-01abc111111" "kno-01abc222222"]
               (get-in parsed [:error :candidates])))
        (is (not (contains? parsed :data))))))

  (testing "dep tree --json on an ambiguous partial id emits ambiguous_id error envelope"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01abc111111--a.md"))
            "---\nid: kno-01abc111111\nstatus: open\n---\n\n# A\n")
      (spit (str (fs/path tmp ".tickets" "kno-01abc222222--b.md"))
            "---\nid: kno-01abc222222\nstatus: open\n---\n\n# B\n")
      (let [{:keys [exit out err]} (run-knot tmp "dep" "tree" "kno-01abc" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err))
        (is (= false (:ok parsed)))
        (is (= "ambiguous_id" (get-in parsed [:error :code])))
        (is (= ["kno-01abc111111" "kno-01abc222222"]
               (get-in parsed [:error :candidates]))))))

  (testing "dep tree --json on an UNKNOWN root id intentionally returns ok:true with data.missing:true"
    ;; Unlike `show`, `dep tree` is tolerant of unknown roots: it renders the
    ;; root as a `[missing]` leaf so consumers can discover broken `:deps`
    ;; refs *via* the parent that links to them. Pinning this here so the
    ;; v0.3 contract asymmetry between show (`ok:false, not_found`) and
    ;; dep tree (`ok:true, data.missing:true`) for the same input is
    ;; deliberate, not accidental.
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]} (run-knot tmp "dep" "tree" "no-such-id" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (str/blank? err))
        (is (= true (:ok parsed)))
        (is (= true (get-in parsed [:data :missing])))
        (is (= "no-such-id" (get-in parsed [:data :id])))))))

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

  (testing "ls --json emits a v0.3 success-envelope wrapping a JSON array"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [exit out err]} (run-knot tmp "ls" "--json")]
        (is (zero? exit) (str "ls --json err=" err))
        (let [trimmed (str/trim out)]
          (is (str/starts-with? trimmed "{"))
          (is (str/ends-with? trimmed "}"))
          (is (str/includes? trimmed "\"schema_version\":1"))
          (is (str/includes? trimmed "\"ok\":true"))
          (is (str/includes? trimmed "\"data\":["))
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

  (testing "knot list --json emits the same envelope shape as knot ls --json"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [exit out err]} (run-knot tmp "list" "--json")]
        (is (zero? exit) (str "list --json err=" err))
        (let [trimmed (str/trim out)]
          (is (str/starts-with? trimmed "{"))
          (is (str/ends-with? trimmed "}"))
          (is (str/includes? trimmed "\"schema_version\":1"))
          (is (str/includes? trimmed "\"ok\":true"))
          (is (str/includes? trimmed "\"data\":["))
          (is (str/includes? trimmed "\"status\":\"open\"")))))))

(deftest show-json-end-to-end-test
  (testing "show --json emits a v0.3 success envelope wrapping the ticket"
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
          (is (str/includes? trimmed "\"schema_version\":1"))
          (is (str/includes? trimmed "\"ok\":true"))
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

(deftest create-mode-shortcut-flags-removed-test
  ;; `--mode <value>` is the only path to set the mode on `knot create`.
  ;; The legacy `--afk` and `--hitl` shortcuts have been removed because
  ;; they baked canonical mode names into CLI parsing (a project that
  ;; customizes :modes — e.g. ["solo" "team"] — would expose shortcuts
  ;; referencing modes it does not have).
  ;;
  ;; `:create` carries `:restrict? true` so the parser rejects the removed
  ;; flags loudly rather than silently absorbing them. A user with stale
  ;; muscle memory typing `--afk` gets a clear unknown-option error, not a
  ;; quietly-wrong ticket whose mode falls through to the default.
  (testing "--afk is rejected as an unknown option"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "create" "Job" "--afk")]
        (is (= 1 exit) (str "expected exit 1, got " exit "; out=" out))
        (is (str/includes? err "Unknown option")
            "stderr must name the unknown-option failure")
        (is (re-find #"(?i)afk" err)
            "stderr must name the offending flag"))))

  (testing "--hitl is rejected as an unknown option"
    (with-tmp tmp
      (let [{:keys [exit err]} (run-knot tmp "create" "Job" "--hitl")]
        (is (= 1 exit) (str "expected exit 1, got " exit "; err=" err))
        (is (str/includes? err "Unknown option"))
        (is (re-find #"(?i)hitl" err)))))

  (testing "--mode hitl still sets the mode (sanity check that --mode works)"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "Hitl job" "--mode" "hitl")]
        (is (zero? exit) (str "create err=" err))
        (let [text (slurp (str/trim out))]
          (is (str/includes? text "mode: hitl"))))))

  (testing "knot create --help still works with :restrict? true"
    ;; The help router intercepts --help before parse-args runs, so
    ;; restrict-mode rejection does not block discoverability.
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "create" "--help")]
        (is (zero? exit) (str "help err=" err))
        (is (str/includes? out "--mode")
            "--mode is still advertised as the canonical entry point")
        (is (not (re-find #"--afk\b" out))
            "help text must not advertise --afk")
        (is (not (re-find #"--hitl\b" out))
            "help text must not advertise --hitl")
        (is (not (re-find #"(?i)shortcut for --mode" out))
            "help text must not describe any --mode shortcut")))))

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
        (is (str/includes? (:out json) "\"schema_version\":1"))
        (is (str/includes? (:out json) "\"ok\":true"))
        (is (str/includes? (:out json) (str "\"id\":\"" a-id "\"")))
        (is (str/includes? (:out json) (str "\"id\":\"" b-id "\"")))))))

(deftest check-detects-hand-edited-cycle-test
  (testing "knot check on a hand-edited cycle: exit 1, dep_cycle row in stdout"
    ;; The CLI rejects cycle-creating `dep` adds, so the only way to land
    ;; one is to bypass it by editing the file directly. `knot check`
    ;; subsumes the role formerly held by `knot dep cycle`.
    (with-tmp tmp
      (let [a-out  (run-knot tmp "create" "Alpha")
            b-out  (run-knot tmp "create" "Beta")
            a-id   (id-from-create-out (:out a-out) "alpha")
            b-id   (id-from-create-out (:out b-out) "beta")
            _      (run-knot tmp "dep" a-id b-id)
            b-path (str/trim (:out b-out))
            [before after] (str/split (slurp b-path) #"---\n" 2)
            _ (spit b-path (str before "---\ndeps:\n  - " a-id "\n" after))
            {:keys [exit out err]} (run-knot tmp "check")]
        (is (= 1 exit) "cycle present -> exit 1")
        (is (str/blank? err) "issues land on stdout, not stderr")
        (is (str/includes? out "dep_cycle"))
        (is (str/includes? out a-id))
        (is (str/includes? out b-id))))))

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
      (run-knot tmp "create" "Afk job"  "--mode" "afk")
      (run-knot tmp "create" "Hitl job" "--mode" "hitl")
      (let [{:keys [exit out err]} (run-knot tmp "ls" "--mode" "afk")]
        (is (zero? exit) (str "ls err=" err))
        (is (str/includes? out "Afk job"))
        (is (not (str/includes? out "Hitl job"))))))

  (testing "ls --status open --mode afk ANDs the filters"
    (with-tmp tmp
      (run-knot tmp "create" "Afk open"  "--mode" "afk")
      (run-knot tmp "create" "Hitl open" "--mode" "hitl")
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
      (run-knot tmp "create" "Afk job"  "--mode" "afk")
      (run-knot tmp "create" "Hitl job" "--mode" "hitl")
      (let [{:keys [exit out err]} (run-knot tmp "ready" "--mode" "afk")]
        (is (zero? exit) (str "ready err=" err))
        (is (str/includes? out "Afk job"))
        (is (not (str/includes? out "Hitl job"))))))

  (testing "ready --mode afk --limit caps after filtering"
    (with-tmp tmp
      (run-knot tmp "create" "Hitl one"   "--mode" "hitl")
      (run-knot tmp "create" "Hitl two"   "--mode" "hitl")
      (run-knot tmp "create" "Afk one"    "--mode" "afk")
      (run-knot tmp "create" "Afk two"    "--mode" "afk")
      (run-knot tmp "create" "Afk three"  "--mode" "afk")
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

  (testing "closed --json returns a v0.3 success-envelope wrapping a JSON array"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Alpha")) "alpha")
            _    (run-knot tmp "close" a-id)
            {:keys [out]} (run-knot tmp "closed" "--json")]
        (is (str/starts-with? (str/trim out) "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"data\":["))
        (is (str/includes? out "\"status\":\"closed\"")))))

  (testing "closed --mode filters by mode"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Afk ticket" "--mode" "afk")) "afk-ticket")
            b-id (id-from-create-out (:out (run-knot tmp "create" "Hitl ticket" "--mode" "hitl")) "hitl-ticket")
            _ (run-knot tmp "close" a-id)
            _ (run-knot tmp "close" b-id)
            {:keys [exit out err]} (run-knot tmp "closed" "--mode" "afk")]
        (is (zero? exit) (str "closed --mode err=" err))
        (is (str/includes? out "Afk ticket"))
        (is (not (str/includes? out "Hitl ticket"))
            "hitl-mode closed ticket filtered out by --mode afk"))))

  (testing "closed --type filters by type"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "A bug" "-t" "bug")) "a-bug")
            b-id (id-from-create-out (:out (run-knot tmp "create" "A task" "-t" "task")) "a-task")
            _ (run-knot tmp "close" a-id)
            _ (run-knot tmp "close" b-id)
            {:keys [exit out err]} (run-knot tmp "closed" "--type" "bug")]
        (is (zero? exit) (str "closed --type err=" err))
        (is (str/includes? out "A bug"))
        (is (not (str/includes? out "A task"))
            "task-type closed ticket filtered out by --type bug"))))

  (testing "contradictory filter yields empty `data` array, not an error"
    ;; Pins the v0.3 contract: contradictory filters are valid and return
    ;; []. `closed --status open` is the canonical case — it cannot match
    ;; any terminal ticket, so the JSON envelope must still be ok=true with
    ;; data=[]. Earlier surfaces rejected this combination at parse time.
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "Live")) "live")
            _ (run-knot tmp "close" a-id)
            {:keys [exit out err]} (run-knot tmp "closed" "--status" "open" "--json")]
        (is (zero? exit) (str "closed --status open --json err=" err))
        (is (str/includes? out "\"ok\":true"))
        (is (str/includes? out "\"data\":[]")
            "empty result renders as data=[], not as an error envelope")))))

(deftest blocked-filter-flags-end-to-end-test
  (testing "blocked --mode filters by mode"
    (with-tmp tmp
      (let [dep-id  (id-from-create-out (:out (run-knot tmp "create" "Dep")) "dep")
            afk-id  (id-from-create-out (:out (run-knot tmp "create" "Afk blocked" "--mode" "afk")) "afk-blocked")
            hitl-id (id-from-create-out (:out (run-knot tmp "create" "Hitl blocked" "--mode" "hitl")) "hitl-blocked")
            _ (run-knot tmp "dep" afk-id dep-id)
            _ (run-knot tmp "dep" hitl-id dep-id)
            {:keys [exit out err]} (run-knot tmp "blocked" "--mode" "afk")]
        (is (zero? exit) (str "blocked --mode err=" err))
        (is (str/includes? out "Afk blocked"))
        (is (not (str/includes? out "Hitl blocked"))
            "hitl-mode blocked ticket filtered out by --mode afk"))))

  (testing "blocked --type filters by type"
    (with-tmp tmp
      (let [dep-id  (id-from-create-out (:out (run-knot tmp "create" "Dep")) "dep")
            bug-id  (id-from-create-out (:out (run-knot tmp "create" "A bug" "-t" "bug")) "a-bug")
            task-id (id-from-create-out (:out (run-knot tmp "create" "A task" "-t" "task")) "a-task")
            _ (run-knot tmp "dep" bug-id dep-id)
            _ (run-knot tmp "dep" task-id dep-id)
            {:keys [exit out err]} (run-knot tmp "blocked" "--type" "bug")]
        (is (zero? exit) (str "blocked --type err=" err))
        (is (str/includes? out "A bug"))
        (is (not (str/includes? out "A task"))
            "task-type blocked ticket filtered out by --type bug")))))

(deftest ls-limit-end-to-end-test
  (testing "list --limit caps the row count"
    (with-tmp tmp
      (run-knot tmp "create" "Alpha")
      (run-knot tmp "create" "Beta")
      (run-knot tmp "create" "Gamma")
      (let [{:keys [exit out err]} (run-knot tmp "list" "--limit" "2")
            ;; Count title-hits rather than id-row prefix — the project prefix
            ;; is derived from the temp dir name, not "kno-".
            title-hits (count (filter #(str/includes? out %) ["Alpha" "Beta" "Gamma"]))]
        (is (zero? exit) (str "list --limit err=" err))
        (is (= 2 title-hits) "--limit 2 shows exactly 2 of the 3 tickets")))))

(deftest prime-filter-all-sections-end-to-end-test
  (testing "prime --type filters in_progress section"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "A bug" "-t" "bug")) "a-bug")
            b-id (id-from-create-out (:out (run-knot tmp "create" "A task" "-t" "task")) "a-task")
            _ (run-knot tmp "start" a-id)
            _ (run-knot tmp "start" b-id)
            {:keys [exit out err]} (run-knot tmp "prime" "--type" "bug")]
        (is (zero? exit) (str "prime --type err=" err))
        (is (str/includes? out "A bug") "bug ticket surfaces in output")
        (is (not (str/includes? out "A task"))
            "task-type ticket filtered out of in_progress section"))))

  (testing "prime --type filters recently_closed section"
    (with-tmp tmp
      (let [a-id (id-from-create-out (:out (run-knot tmp "create" "A bug" "-t" "bug")) "a-bug")
            b-id (id-from-create-out (:out (run-knot tmp "create" "A task" "-t" "task")) "a-task")
            _ (run-knot tmp "close" a-id)
            _ (run-knot tmp "close" b-id)
            {:keys [exit out err]} (run-knot tmp "prime" "--type" "bug")]
        (is (zero? exit) (str "prime --type recently_closed err=" err))
        (is (str/includes? out "A bug") "bug ticket surfaces in recently_closed")
        (is (not (str/includes? out "A task"))
            "task-type ticket filtered out of recently_closed section")))))

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

  (testing "prime --json emits a v0.3 success envelope with documented snake_case keys"
    (with-tmp tmp
      (run-knot tmp "create" "Live ticket")
      (let [{:keys [exit out err]} (run-knot tmp "prime" "--json")]
        (is (zero? exit) (str "prime --json err=" err))
        (is (str/starts-with? (str/trim out) "{"))
        (is (str/includes? out "\"schema_version\":1"))
        (is (str/includes? out "\"ok\":true"))
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
      (run-knot tmp "create" "Afk job"  "--mode" "afk")
      (run-knot tmp "create" "Hitl job" "--mode" "hitl")
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

  (testing "custom :statuses + :active-status: init, create, start, prime end-to-end"
    (with-tmp tmp
      (run-knot tmp "init")
      ;; Replace the stub config with a custom statuses workflow.
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:statuses          ["open" "active" "closed"]
                     :terminal-statuses #{"closed"}
                     :active-status     "active"}))
      (let [{:keys [exit out]} (run-knot tmp "create" "Custom workflow")
            id (id-from-create-out out "custom-workflow")]
        (is (zero? exit) "create succeeds under custom :statuses")
        (let [{:keys [exit err]} (run-knot tmp "start" id)]
          (is (zero? exit)
              (str "start succeeds under custom :statuses; err=" err))
          (is (not (str/includes? err "validation"))
              "start does not bounce off status validation"))
        (let [{:keys [out]} (run-knot tmp "show" id "--json")]
          (is (str/includes? out "\"status\":\"active\"")
              "ticket landed in the configured active status, not in_progress"))
        (let [{:keys [exit out err]} (run-knot tmp "prime")]
          (is (zero? exit) (str "prime err=" err))
          (is (str/includes? out "## In Progress")
              "ticket in :active-status surfaces under ## In Progress")
          (is (str/includes? out "Custom workflow"))
          (is (str/includes? out "transition to active")
              "Commands cheatsheet `knot start` line names the active status")))))

  (testing "custom :statuses without :active-status fails fast with a strict-validation error on start"
    (with-tmp tmp
      ;; Set up a valid config and create a ticket under it, so we can
      ;; later run `start` against a real id once the config is broken.
      (run-knot tmp "init")
      (let [a-out (run-knot tmp "create" "Will be started")
            a-id  (id-from-create-out (:out a-out) "will-be-started")]
        ;; Now break the config: override :statuses to a list that does
        ;; NOT contain "in_progress", without supplying :active-status.
        ;; The default :active-status (in_progress) no longer satisfies
        ;; the validator, and any command that reads config — including
        ;; the original-bug surface, `start` — must fail fast.
        (spit (str (fs/path tmp ".knot.edn"))
              (pr-str {:statuses          ["open" "active" "closed"]
                       :terminal-statuses #{"closed"}}))
        (let [{:keys [exit err]} (run-knot tmp "start" a-id)]
          (is (= 1 exit) "start must abort on config validation")
          (is (str/includes? err "active-status")
              "stderr names :active-status as the offending key")))))

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

(defn- id-of [out slug-pat]
  (->> (str/trim out)
       fs/file-name
       str
       (re-matches (re-pattern (str "(.+)--" slug-pat "\\.md")))
       second))

(deftest mutating-json-end-to-end-test
  (testing "create --json emits a v0.3 envelope wrapping the new ticket"
    (with-tmp tmp
      (run-knot tmp "init" "--prefix" "kno")
      (let [{:keys [exit out err]} (run-knot tmp "create" "Hello world" "--json")]
        (is (zero? exit) (str "create --json err=" err))
        (let [parsed (json/parse-string (str/trim out) true)]
          (is (= 1 (:schema_version parsed)))
          (is (= true (:ok parsed)))
          (is (= "Hello world" (get-in parsed [:data :title])))
          (is (= "open" (get-in parsed [:data :status])))
          (is (str/starts-with? (get-in parsed [:data :id]) "kno-"))))))

  (testing "start --json emits the post-mutation ticket envelope"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Task")
            id (id-of out "task")
            {:keys [exit out err]} (run-knot tmp "start" id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit) (str "start --json err=" err))
        (is (= true (:ok parsed)))
        (is (= "in_progress" (get-in parsed [:data :status])))
        (is (not (contains? parsed :meta))
            "start emits no :meta — never a terminal transition"))))

  (testing "close --json populates meta.archived_to"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Closing task")
            id (id-of out "closing-task")
            {:keys [exit out err]} (run-knot tmp "close" id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit) (str "close --json err=" err))
        (is (= true (:ok parsed)))
        (is (= "closed" (get-in parsed [:data :status])))
        (is (str/includes? (get-in parsed [:meta :archived_to]) "/archive/")
            "close --json's :meta.archived_to points at the archive dir"))))

  (testing "close --json --summary embeds the note in :data.body"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Closing task")
            id (id-of out "closing-task")
            {:keys [exit out]} (run-knot tmp "close" id "--summary" "Shipped." "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (str/includes? (get-in parsed [:data :body]) "Shipped.")))))

  (testing "reopen --json reverses close cleanly"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Reopen me")
            id (id-of out "reopen-me")
            _  (run-knot tmp "close" id)
            {:keys [exit out]} (run-knot tmp "reopen" id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= true (:ok parsed)))
        (is (= "open" (get-in parsed [:data :status])))
        (is (not (contains? parsed :meta))))))

  (testing "status --json on a missing id emits not_found envelope (exit 1)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]}
            (run-knot tmp "status" "kno-ghost" "open" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err)
            "json error envelope goes to stdout, not stderr")
        (is (= false (:ok parsed)))
        (is (= "not_found" (get-in parsed [:error :code]))))))

  (testing "dep --json emits the from ticket post-mutation"
    (with-tmp tmp
      (let [{from-out :out} (run-knot tmp "create" "From")
            {to-out :out}   (run-knot tmp "create" "To")
            from-id (id-of from-out "from")
            to-id   (id-of to-out "to")
            {:keys [exit out]} (run-knot tmp "dep" from-id to-id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= true (:ok parsed)))
        (is (= from-id (get-in parsed [:data :id])))
        (is (= [to-id] (get-in parsed [:data :deps]))))))

  (testing "dep --json on a cycle emits a cycle error envelope (exit 1)"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "dep" a-id b-id)
            {:keys [exit out err]}
            (run-knot tmp "dep" b-id a-id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err))
        (is (= false (:ok parsed)))
        (is (= "cycle" (get-in parsed [:error :code])))
        (is (vector? (get-in parsed [:error :cycle]))))))

  (testing "undep --json emits the from ticket with the dep removed"
    (with-tmp tmp
      (let [{from-out :out} (run-knot tmp "create" "From")
            {to-out :out}   (run-knot tmp "create" "To")
            from-id (id-of from-out "from")
            to-id   (id-of to-out "to")
            _       (run-knot tmp "dep" from-id to-id)
            {:keys [exit out]} (run-knot tmp "undep" from-id to-id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= true (:ok parsed)))
        (is (not (contains? (:data parsed) :deps))
            "removing the last dep drops :deps in the envelope"))))

  (testing "link --json emits an array of post-mutation tickets (no body)"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "Alpha")
            {b-out :out} (run-knot tmp "create" "Beta")
            a-id (id-of a-out "alpha")
            b-id (id-of b-out "beta")
            {:keys [exit out]} (run-knot tmp "link" a-id b-id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= true (:ok parsed)))
        (is (vector? (:data parsed)))
        (is (= 2 (count (:data parsed))))
        (is (= #{a-id b-id} (set (map :id (:data parsed)))))
        (is (not (contains? (get-in parsed [:data 0]) :body))
            "list-style envelope excludes :body"))))

  (testing "unlink --json emits an array of post-mutation tickets"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "Alpha")
            {b-out :out} (run-knot tmp "create" "Beta")
            a-id (id-of a-out "alpha")
            b-id (id-of b-out "beta")
            _    (run-knot tmp "link" a-id b-id)
            {:keys [exit out]} (run-knot tmp "unlink" a-id b-id "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= true (:ok parsed)))
        (is (= 2 (count (:data parsed)))))))

  (testing "add-note --json emits the post-mutation ticket including the note"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Noted")
            id (id-of out "noted")
            {:keys [exit out]} (run-knot tmp "add-note" id "first note" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= true (:ok parsed)))
        (is (= id (get-in parsed [:data :id])))
        (is (str/includes? (get-in parsed [:data :body]) "first note")))))

  (testing "add-note --json on a missing id emits not_found envelope (exit 1)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]}
            (run-knot tmp "add-note" "kno-ghost" "x" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err))
        (is (= false (:ok parsed)))
        (is (= "not_found" (get-in parsed [:error :code])))))))

(deftest update-end-to-end-test
  (testing "update <id> --title rewrites frontmatter and prints the saved path"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Original")
            id (id-of out "original")
            {:keys [exit out err]}
            (run-knot tmp "update" id "--title" "Renamed")]
        (is (zero? exit) (str "update --title err=" err))
        (is (str/includes? (str/trim out) ".tickets")
            "stdout is the saved path")
        (let [{shown :out} (run-knot tmp "show" id)]
          (is (str/includes? shown "Renamed"))
          (is (not (str/includes? shown "Original")))))))

  (testing "update --description replaces a body section visible via show"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "T"
                                    "--description" "Old description.")
            id (id-of out "t")
            {:keys [exit err]}
            (run-knot tmp "update" id "--description" "Brand new desc.")]
        (is (zero? exit) (str "update --description err=" err))
        (let [{shown :out} (run-knot tmp "show" id)]
          (is (str/includes? shown "Brand new desc."))
          (is (not (str/includes? shown "Old description.")))))))

  (testing "update --body replaces the whole body, including dash-prefixed values"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "T")
            id (id-of out "t")
            {:keys [exit err]}
            (run-knot tmp "update" id "--body" "- [ ] task one\n- [ ] task two\n")]
        (is (zero? exit) (str "update --body err=" err))
        (let [{shown :out} (run-knot tmp "show" id)]
          (is (str/includes? shown "- [ ] task one"))
          (is (str/includes? shown "- [ ] task two"))))))

  (testing "update --body conflicts with --description and exits non-zero"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "T")
            id (id-of out "t")
            {:keys [exit err]}
            (run-knot tmp "update" id "--body" "x" "--description" "y")]
        (is (= 1 exit))
        (is (str/includes? err "mutually exclusive")))))

  (testing "update --json emits a v0.3 envelope wrapping the post-mutation ticket"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "T")
            id (id-of out "t")
            {:keys [exit out err]}
            (run-knot tmp "update" id "--title" "New" "--priority" "1" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit) (str "update --json err=" err))
        (is (= 1 (:schema_version parsed)))
        (is (= true (:ok parsed)))
        (is (= "New" (get-in parsed [:data :title])))
        (is (= 1 (get-in parsed [:data :priority])))
        (is (not (contains? parsed :meta))
            "update never archives — no :meta slot"))))

  (testing "update --json on a missing id emits not_found envelope (exit 1)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]}
            (run-knot tmp "update" "kno-ghost" "--title" "X" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err)
            "json error envelope goes to stdout, not stderr")
        (is (= false (:ok parsed)))
        (is (= "not_found" (get-in parsed [:error :code])))))))

(deftest update-external-ref-clear-end-to-end-test
  (testing "update --external-ref \"\" clears external_refs from the CLI"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "T"
                                    "--external-ref" "JIRA-1")
            id (id-of out "t")
            {:keys [exit out err]}
            (run-knot tmp "update" id "--external-ref" "" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit) (str "update --external-ref \"\" err=" err))
        (is (= true (:ok parsed)))
        (is (not (contains? (:data parsed) :external_refs))
            "blank --external-ref must clear external_refs, not store [\"\"]")))))

(deftest create-body-flag-not-consumed-end-to-end-test
  ;; Regression guard for kno-01kqgqcqmy19 review: --body is `update`'s
  ;; whole-body-replace flag, not a `create` flag. Adding it to a global
  ;; body-flag-extraction map silently routed `--body x` into create's
  ;; body-opts where it was dropped on the floor. Scope --body to update.
  ;;
  ;; With `:restrict? true` on `:create` (kno-01kqgqa7wnep), the contract is
  ;; now stronger: `--body` is rejected as an unknown option rather than
  ;; silently absorbed. The user gets a clear error instead of a ticket
  ;; whose `--body` value vanished.
  (testing "create --body x is rejected as an unknown option (not silently dropped)"
    (with-tmp tmp
      (let [{:keys [exit out err]}
            (run-knot tmp "create" "T" "--body" "should-not-leak")]
        (is (= 1 exit) (str "expected exit 1, got " exit "; out=" out))
        (is (str/includes? err "Unknown option")
            "stderr must name the unknown-option failure")
        (is (re-find #"(?i)body" err)
            "stderr must name the offending flag")))))

(deftest info-end-to-end-test
  (testing "knot info in a discovered project prints the five fixed sections (exit 0)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out err]} (run-knot tmp "info")]
        (is (zero? exit) (str "info exited non-zero. err=" err))
        (is (str/blank? err))
        (is (str/includes? out "## Project"))
        (is (str/includes? out "## Paths"))
        (is (str/includes? out "## Defaults"))
        (is (str/includes? out "## Allowed Values"))
        (is (str/includes? out "## Counts")))))

  (testing "knot info --json emits a v0.3 envelope with all five nested sections"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out]} (run-knot tmp "info" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (zero? exit))
        (is (= 1     (:schema_version parsed)))
        (is (= true  (:ok parsed)))
        (let [d (:data parsed)]
          (is (map? (:project d)))
          (is (map? (:paths d)))
          (is (map? (:defaults d)))
          (is (map? (:allowed_values d)))
          (is (map? (:counts d)))))))

  (testing "knot info uses the derived prefix when .knot.edn omits :prefix"
    (with-tmp tmp
      ;; The tmp dir name is the auto-derive source (per ticket/derive-prefix).
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [{:keys [exit out]}  (run-knot tmp "info" "--json")
            project (-> out str/trim (json/parse-string true) :data :project)
            expected-prefix     (ticket/derive-prefix (str (fs/file-name tmp)))]
        (is (zero? exit))
        (is (= expected-prefix (:prefix project))
            "prefix derives from the project directory name when config has no :prefix")
        (is (= false (:config_present project))
            ".tickets/-only project (no .knot.edn) reports config_present=false"))))

  (testing "knot info uses :prefix from .knot.edn when configured"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".knot.edn")) "{:prefix \"abc\"}")
      (let [{:keys [exit out]} (run-knot tmp "info" "--json")
            project (-> out str/trim (json/parse-string true) :data :project)]
        (is (zero? exit))
        (is (= "abc" (:prefix project)))
        (is (= true  (:config_present project))))))

  (testing "knot info from outside any project exits 1 with stderr message"
    (with-tmp tmp
      ;; No .knot.edn and no .tickets/ — make sure the walk-up does not
      ;; cross into the project that hosts the test runner. with-tmp
      ;; creates the dir in the OS tmp area, so this is naturally bounded.
      (let [{:keys [exit out err]} (run-knot tmp "info")]
        (is (= 1 exit))
        (is (str/blank? out))
        (is (str/includes? err "no project")))))

  (testing "knot info --json from outside any project emits the no_project error envelope"
    (with-tmp tmp
      (let [{:keys [exit out err]} (run-knot tmp "info" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err)
            "JSON consumers parse stdout — error envelope goes there, not stderr")
        (is (= 1     (:schema_version parsed)))
        (is (= false (:ok parsed)))
        (is (= "no_project" (get-in parsed [:error :code])))
        (is (not (contains? parsed :data))))))

  (testing "knot info --json with invalid .knot.edn emits the config_invalid error envelope"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".knot.edn")) "{:prefix not-a-string-and-broken-edn (((")
      (let [{:keys [exit out err]} (run-knot tmp "info" "--json")
            parsed (json/parse-string (str/trim out) true)]
        (is (= 1 exit))
        (is (str/blank? err))
        (is (= false (:ok parsed)))
        (is (= "config_invalid" (get-in parsed [:error :code]))))))

  (testing "knot info --no-color is accepted and does not change the plain text output"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [a (run-knot tmp "info")
            b (run-knot tmp "info" "--no-color")]
        (is (zero? (:exit a)))
        (is (zero? (:exit b)))
        (is (= (:out a) (:out b))
            "--no-color is a no-op on info text output (always plain)"))))

  (testing "knot info counts include malformed ticket files (no parsing)"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "kno-01a--good.md"))
            "---\nid: kno-01a\n---\n\nBody\n")
      (spit (str (fs/path tmp ".tickets" "kno-01b--broken.md"))
            "garbage with no frontmatter\n")
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01x--archived.md"))
            "---\nid: kno-01x\n---\n")
      (let [{:keys [exit out]} (run-knot tmp "info" "--json")
            counts (-> out str/trim (json/parse-string true) :data :counts)]
        (is (zero? exit))
        (is (= 2 (:live_count counts)))
        (is (= 1 (:archive_count counts)))
        (is (= 3 (:total_count counts)))))))

