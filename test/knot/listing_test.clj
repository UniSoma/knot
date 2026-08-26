(ns knot.listing-test
  (:require [clojure.test :refer [deftest is testing]]
            [knot.listing :as listing]))

(def ^:private terminal #{"closed"})

(defn- ticket
  [id status & {:as extras}]
  {:frontmatter (merge {:id id :status status :priority 2 :created "2026-01-01T00:00:00Z"}
                       extras)
   :body ""})

(def ^:private corpus
  [(ticket "a" "open")
   (ticket "bb" "open" :deps ["a"] :tags ["p0"])
   (ticket "c" "closed" :closed "2026-01-02T00:00:00Z")
   (ticket "d" "closed" :closed "2026-01-03T00:00:00Z")
   (ticket "e" "closed")
   (ticket "f" "open" :parent "a" :links ["bb"])])

(defn- ids [rows] (mapv #(get-in % [:frontmatter :id]) rows))

(defn- rows [view] (listing/rows corpus terminal view))

(deftest source-test
  (testing "list starts from live tickets in corpus order"
    (is (= ["a" "bb" "f"] (ids (rows {:source :list})))))
  (testing "list with a :status filter starts from the whole corpus"
    (is (= ["c" "d" "e"] (ids (rows {:source :list :filters {:status #{"closed"}}})))))
  (testing "ready starts from the ready set"
    (is (= #{"a" "f"} (set (ids (rows {:source :ready}))))))
  (testing "blocked starts from the blocked set"
    (is (= ["bb"] (ids (rows {:source :blocked})))))
  (testing "closed starts from terminal tickets, newest close first, stamp-less last"
    (is (= ["d" "c" "e"] (ids (rows {:source :closed}))))))

(deftest scope-test
  (testing "closure keeps members of the seeds' closure over the corpus, over the chosen axes"
    (is (= ["a" "bb" "f"] (ids (rows {:source :list :scope {:closure ["a"]}}))))
    (is (= ["a" "bb"] (ids (rows {:source :list :scope {:closure ["a"] :via #{:deps}}})))))
  (testing "component keeps members of the seed's live component"
    (is (= ["a" "bb" "f"] (ids (rows {:source :list :scope {:component "f"}})))))
  (testing "a closed component seed fails fast"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is closed"
                          (rows {:source :list :scope {:component "c"}})))))

(deftest filter-test
  (testing "display filters narrow the scoped rows"
    (is (= ["bb"] (ids (rows {:source :list :filters {:tag #{"p0"}}})))))
  (testing "criteria projects only non-empty filter keys out of opts"
    (is (= {:tag #{"p0"} :mode #{"afk"}}
           (listing/criteria {:tag #{"p0"} :mode #{"afk"} :status #{} :assignee nil
                              :json? true :limit 3})))))

(deftest limit-test
  (testing "limit truncates after sort"
    (is (= ["d"] (ids (rows {:source :closed :limit 1})))))
  (testing "nil is no limit"
    (is (= 3 (count (rows {:source :list :limit nil})))))
  (testing "zero and negatives throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (rows {:source :list :limit 0})))))

(defn- by-id [rows id] (some #(when (= id (get-in % [:frontmatter :id])) %) rows))

(deftest column-attachment-test
  (testing "live views attach the graph metrics to their rows"
    (let [r (rows {:source :list})
          a (by-id r "a")
          f (by-id r "f")]
      (is (= 1 (:leverage a)))
      (is (= 1 (:coupling f)) "parent is not a coupling axis; only the link to bb counts")
      (is (= 0 (:level a)))
      (is (= 1 (:level (by-id r "bb"))))
      (is (= 1 (:cc a)))
      (is (= [0 1] (:children-progress a)))
      (is (not (contains? f :children-progress)))))
  (testing "the closed view attaches umbrella progress but no graph metric"
    (let [c (by-id (rows {:source :closed}) "c")]
      (is (not (contains? c :leverage)))
      (is (not (contains? c :coupling)))
      (is (not (contains? c :level)))
      (is (not (contains? c :cc)))))
  (testing "every column is one declaration with the fields output and help iterate"
    (is (= [:acceptance :children :leverage :coupling :level :cc]
           (mapv :key listing/columns)))
    (doseq [c listing/columns]
      (is (every? #(contains? c %) [:key :header :align :sources :shown? :cell :json])
          (str (:key c))))))
