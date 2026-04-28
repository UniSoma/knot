(ns knot.config-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [knot.config :as config]))

(defn- mkdir-p! [path]
  (fs/create-dirs path))

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
