(ns knot.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [knot.query :as query]))

(def ^:private terminal-statuses #{"closed"})

(def ^:private sample-tickets
  [{:frontmatter {:id "a" :status "open"}}
   {:frontmatter {:id "b" :status "in_progress"}}
   {:frontmatter {:id "c" :status "closed"}}])

(deftest non-terminal-test
  (testing "non-terminal filters out tickets whose status is in terminal-statuses"
    (let [result (query/non-terminal sample-tickets terminal-statuses)
          ids    (set (map #(get-in % [:frontmatter :id]) result))]
      (is (= #{"a" "b"} ids))))

  (testing "non-terminal with multiple terminal statuses excludes them all"
    (let [result (query/non-terminal sample-tickets #{"closed" "in_progress"})
          ids    (set (map #(get-in % [:frontmatter :id]) result))]
      (is (= #{"a"} ids))))

  (testing "non-terminal returns an empty seq when given an empty input"
    (is (empty? (query/non-terminal [] terminal-statuses))))

  (testing "non-terminal preserves input order"
    (let [result (query/non-terminal sample-tickets terminal-statuses)]
      (is (= ["a" "b"] (mapv #(get-in % [:frontmatter :id]) result))))))
