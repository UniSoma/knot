(ns knot.config-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.config :as config]))

(defn- mkdir-p! [path]
  (fs/create-dirs path))

(defmacro ^:private with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(deftest defaults-test
  (testing "default config carries the v0 schema defaults"
    (let [d (config/defaults)]
      (is (= ".tickets" (:tickets-dir d)))
      (is (= "task" (:default-type d)))
      (is (= 2 (:default-priority d)))
      (is (= ["open" "in_progress" "closed"] (:statuses d)))
      (is (= #{"closed"} (:terminal-statuses d)))
      (is (= ["bug" "feature" "task" "epic" "chore"] (:types d)))
      (is (= ["afk" "hitl"] (:modes d)))
      (is (= "hitl" (:default-mode d))))))

(deftest find-project-root-test
  (testing "walks up to find the dir containing .tickets/"
    (let [tmp (str (fs/create-temp-dir))
          root (str (fs/path tmp "proj"))
          deep (str (fs/path root "a" "b" "c"))]
      (try
        (mkdir-p! deep)
        (mkdir-p! (fs/path root ".tickets"))
        (is (= (str (fs/canonicalize root))
               (str (fs/canonicalize (config/find-project-root deep)))))
        (finally
          (fs/delete-tree tmp)))))

  (testing "returns nil when no marker is found anywhere up the tree"
    (let [tmp (str (fs/create-temp-dir))
          deep (str (fs/path tmp "x" "y"))]
      (try
        (mkdir-p! deep)
        ;; ancestors of /tmp may have markers we don't control; canonicalize
        ;; to the temp tree only by looking up to tmp
        (is (= nil (config/find-project-root-within deep tmp)))
        (finally
          (fs/delete-tree tmp))))))

(deftest load-config-test
  (testing "missing .knot.edn returns the defaults"
    (with-tmp tmp
      (mkdir-p! (fs/path tmp ".tickets"))
      (is (= (config/defaults) (config/load-config tmp)))))

  (testing "fully-specified .knot.edn replaces defaults across all keys"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:tickets-dir       "tasks"
                     :prefix            "abc"
                     :default-assignee  "alice"
                     :default-type      "feature"
                     :default-priority  0
                     :statuses          ["open" "review" "done"]
                     :terminal-statuses #{"done"}
                     :types             ["feature" "chore"]
                     :modes             ["afk" "hitl"]
                     :default-mode      "afk"}))
      (let [c (config/load-config tmp)]
        (is (= "tasks" (:tickets-dir c)))
        (is (= "abc" (:prefix c)))
        (is (= "alice" (:default-assignee c)))
        (is (= "feature" (:default-type c)))
        (is (= 0 (:default-priority c)))
        (is (= ["open" "review" "done"] (:statuses c)))
        (is (= #{"done"} (:terminal-statuses c)))
        (is (= ["feature" "chore"] (:types c)))
        (is (= ["afk" "hitl"] (:modes c)))
        (is (= "afk" (:default-mode c))))))

  (testing "partial .knot.edn merges with defaults — absent keys keep default values"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:default-type "feature"
                     :default-priority 0}))
      (let [c (config/load-config tmp)]
        (is (= "feature" (:default-type c)))
        (is (= 0 (:default-priority c)))
        (is (= ".tickets" (:tickets-dir c)) "absent key kept default")
        (is (= ["open" "in_progress" "closed"] (:statuses c)) "absent key kept default")
        (is (= ["afk" "hitl"] (:modes c)) "absent key kept default"))))

  (testing "unknown keys are dropped with a stderr warning, known keys still applied"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:default-type "feature"
                     :nonsense     "ignored"
                     :another-bad  42}))
      (let [err (java.io.StringWriter.)
            c   (binding [*err* err]
                  (config/load-config tmp))]
        (is (= "feature" (:default-type c)))
        (is (not (contains? c :nonsense)))
        (is (not (contains? c :another-bad)))
        (is (str/includes? (str err) "nonsense") "stderr should mention the unknown key")
        (is (str/includes? (str err) "another-bad") "stderr should mention the other unknown key")))))

(defn- write-config! [dir m]
  (spit (str (fs/path dir ".knot.edn")) (pr-str m)))

(defn- load-throws
  "Run `load-config` against `dir` and return the thrown ex-message, or
   nil if no exception. Suppresses stderr so test output stays clean."
  [dir]
  (try
    (binding [*err* (java.io.StringWriter.)]
      (config/load-config dir))
    nil
    (catch Exception e (.getMessage e))))

(deftest load-config-validation-test
  (testing ":default-priority must be an integer in 0..4"
    (with-tmp tmp
      (write-config! tmp {:default-priority "high"})
      (let [msg (load-throws tmp)]
        (is (some? msg))
        (is (str/includes? msg "default-priority"))))
    (with-tmp tmp
      (write-config! tmp {:default-priority 9})
      (let [msg (load-throws tmp)]
        (is (some? msg))
        (is (str/includes? msg "default-priority")))))

  (testing ":tickets-dir must be a non-blank string"
    (with-tmp tmp
      (write-config! tmp {:tickets-dir 42})
      (is (str/includes? (load-throws tmp) "tickets-dir")))
    (with-tmp tmp
      (write-config! tmp {:tickets-dir ""})
      (is (str/includes? (load-throws tmp) "tickets-dir"))))

  (testing ":statuses must be a non-empty list of strings"
    (with-tmp tmp
      (write-config! tmp {:statuses []})
      (is (str/includes? (load-throws tmp) "statuses")))
    (with-tmp tmp
      (write-config! tmp {:statuses ["open" 7]})
      (is (str/includes? (load-throws tmp) "statuses"))))

  (testing ":terminal-statuses must be a subset of :statuses"
    (with-tmp tmp
      (write-config! tmp {:statuses ["open" "done"]
                          :terminal-statuses #{"closed"}})
      (let [msg (load-throws tmp)]
        (is (some? msg))
        (is (str/includes? msg "terminal-statuses")))))

  (testing ":default-type must be one of :types"
    (with-tmp tmp
      (write-config! tmp {:types ["bug" "task"]
                          :default-type "feature"})
      (is (str/includes? (load-throws tmp) "default-type"))))

  (testing ":default-mode must be one of :modes"
    (with-tmp tmp
      (write-config! tmp {:modes ["afk" "hitl"]
                          :default-mode "auto"})
      (is (str/includes? (load-throws tmp) "default-mode"))))

  (testing ":prefix must be a non-empty [a-z0-9]+ string"
    (with-tmp tmp
      (write-config! tmp {:prefix "BAD!"})
      (is (str/includes? (load-throws tmp) "prefix"))))

  (testing "valid full config does NOT throw"
    (with-tmp tmp
      (write-config! tmp {:tickets-dir       "tasks"
                          :prefix            "abc"
                          :default-assignee  "alice"
                          :default-type      "feature"
                          :default-priority  0
                          :statuses          ["open" "review" "done"]
                          :terminal-statuses #{"done"}
                          :types             ["feature" "chore"]
                          :modes             ["afk" "hitl"]
                          :default-mode      "afk"})
      (is (nil? (load-throws tmp))))))

(defn- canon [p] (str (fs/canonicalize p)))

(deftest discover-test
  (testing "discover walks up from a deeply-nested cwd to find the root"
    (with-tmp tmp
      (let [root (str (fs/path tmp "proj"))
            deep (str (fs/path root "a" "b" "c" "d"))]
        (mkdir-p! deep)
        (mkdir-p! (fs/path root ".tickets"))
        (let [r (config/discover deep)]
          (is (= (canon root) (canon (:project-root r))))
          (is (= ".tickets" (-> r :config :tickets-dir)))))))

  (testing "when both markers exist at different ancestors, the nearest wins"
    ;; cwd ─ b/ has .tickets/  (nearest)
    ;;     ─ a/  has .knot.edn (further up)
    (with-tmp tmp
      (let [a    (str (fs/path tmp "a"))
            b    (str (fs/path a "b"))
            cwd  (str (fs/path b "c"))]
        (mkdir-p! cwd)
        (mkdir-p! (fs/path b ".tickets"))
        (write-config! a {:default-type "feature"})
        (let [r (config/discover cwd)]
          (is (= (canon b) (canon (:project-root r)))
              "nearest marker (.tickets at b) should be the project root")
          (is (= "task" (-> r :config :default-type))
              "config from a/ should NOT leak into b/")))))

  (testing "when both markers exist at different ancestors, .knot.edn nearer wins"
    (with-tmp tmp
      (let [a    (str (fs/path tmp "a"))
            b    (str (fs/path a "b"))
            cwd  (str (fs/path b "c"))]
        (mkdir-p! cwd)
        (mkdir-p! (fs/path a ".tickets"))
        (write-config! b {:default-type "feature"})
        (let [r (config/discover cwd)]
          (is (= (canon b) (canon (:project-root r))))
          (is (= "feature" (-> r :config :default-type)))))))

  (testing "config wins on conflict: :tickets-dir from .knot.edn overrides on-disk .tickets/"
    (with-tmp tmp
      (let [root (str (fs/path tmp "proj"))]
        (mkdir-p! root)
        (mkdir-p! (fs/path root ".tickets"))
        (write-config! root {:tickets-dir "tasks"})
        (let [r (config/discover root)]
          (is (= (canon root) (canon (:project-root r))))
          (is (= "tasks" (-> r :config :tickets-dir))
              "config :tickets-dir wins over the on-disk .tickets/ directory")))))

  (testing "discover returns nil when no marker is found"
    (with-tmp tmp
      (let [deep (str (fs/path tmp "x" "y"))]
        (mkdir-p! deep)
        (is (nil? (config/discover-within deep tmp)))))))
