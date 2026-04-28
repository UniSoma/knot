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

(defn- ticket
  "Test helper: build a ticket map from id, status, deps, and optional kvs."
  [id status deps & {:as extras}]
  {:frontmatter (merge {:id id :status status :deps deps} extras)
   :body        (str "# " id "\n")})

(deftest would-create-cycle?-test
  (testing "self-loop: from = to returns the offending path"
    (let [tickets [(ticket "a" "open" [])]]
      (is (= ["a" "a"] (query/would-create-cycle? tickets "a" "a")))))

  (testing "simple back-edge: a→b exists, adding b→a creates cycle"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" [])]]
      ;; the cycle starts at b (the new edge target) and ends at b again
      (is (= ["b" "a" "b"] (query/would-create-cycle? tickets "b" "a")))))

  (testing "longer cycle: a→b→c, adding c→a closes the loop"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" ["c"])
                   (ticket "c" "open" [])]]
      (is (= ["c" "a" "b" "c"] (query/would-create-cycle? tickets "c" "a")))))

  (testing "legal DAG: adding parallel dep does not create a cycle"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" [])
                   (ticket "c" "open" [])]]
      (is (nil? (query/would-create-cycle? tickets "a" "c")))))

  (testing "diamond: shared sub-dep does not create a cycle"
    (let [tickets [(ticket "a" "open" ["b" "c"])
                   (ticket "b" "open" ["d"])
                   (ticket "c" "open" ["d"])
                   (ticket "d" "open" [])]]
      (is (nil? (query/would-create-cycle? tickets "b" "c")))))

  (testing "missing target ticket: no cycle (broken ref is not a cycle)"
    (let [tickets [(ticket "a" "open" [])]]
      (is (nil? (query/would-create-cycle? tickets "a" "ghost"))))))

(defn- canonicalize-cycle
  "Rotate a cycle path so its smallest id is first; drop the trailing
   duplicate. Lets tests assert cycle membership without depending on
   the DFS entry point."
  [path]
  (let [open  (vec (butlast path))
        min-i (reduce (fn [a b]
                        (if (neg? (compare (nth open b) (nth open a))) b a))
                      0
                      (range 1 (count open)))]
    (vec (concat (subvec open min-i) (subvec open 0 min-i)))))

(deftest project-cycles-test
  (testing "empty input: no cycles"
    (is (empty? (query/project-cycles []))))

  (testing "pure DAG: no cycles"
    (let [tickets [(ticket "a" "open" ["b" "c"])
                   (ticket "b" "open" ["d"])
                   (ticket "c" "open" ["d"])
                   (ticket "d" "open" [])]]
      (is (empty? (query/project-cycles tickets)))))

  (testing "self-loop is reported as a single-id cycle"
    (let [tickets [(ticket "a" "open" ["a"])]
          cycles  (query/project-cycles tickets)]
      (is (= 1 (count cycles)))
      (is (= ["a" "a"] (first cycles)))))

  (testing "single back-edge cycle: a→b→a"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" ["a"])]
          cycles  (set (map canonicalize-cycle (query/project-cycles tickets)))]
      (is (= #{["a" "b"]} cycles))))

  (testing "multiple disjoint cycles"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" ["a"])
                   (ticket "c" "open" ["d"])
                   (ticket "d" "open" ["c"])]
          cycles  (set (map canonicalize-cycle (query/project-cycles tickets)))]
      (is (= #{["a" "b"] ["c" "d"]} cycles))))

  (testing "missing dep refs do not produce phantom cycles"
    (let [tickets [(ticket "a" "open" ["ghost"])]]
      (is (empty? (query/project-cycles tickets))))))

(deftest ready-test
  (testing "open ticket with no deps is ready"
    (let [tickets [(ticket "a" "open" [])]]
      (is (= ["a"] (mapv #(get-in % [:frontmatter :id])
                         (query/ready tickets terminal-statuses))))))

  (testing "open ticket with all-terminal deps is ready"
    (let [tickets [(ticket "a" "open" ["b" "c"])
                   (ticket "b" "closed" [])
                   (ticket "c" "closed" [])]]
      (is (= ["a"] (mapv #(get-in % [:frontmatter :id])
                         (query/ready tickets terminal-statuses))))))

  (testing "in_progress ticket with all-terminal deps is ready"
    (let [tickets [(ticket "a" "in_progress" ["b"])
                   (ticket "b" "closed" [])]]
      (is (= ["a"] (mapv #(get-in % [:frontmatter :id])
                         (query/ready tickets terminal-statuses))))))

  (testing "open ticket with one non-terminal dep is NOT ready (but its dep is)"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" [])]
          ids     (set (map #(get-in % [:frontmatter :id])
                            (query/ready tickets terminal-statuses)))]
      ;; a is blocked by b; b has no deps, so b is ready.
      (is (= #{"b"} ids))))

  (testing "closed ticket is never ready"
    (let [tickets [(ticket "a" "closed" [])]]
      (is (empty? (query/ready tickets terminal-statuses)))))

  (testing "missing dep ref blocks readiness (broken ref is not terminal)"
    (let [tickets [(ticket "a" "open" ["ghost"])]]
      (is (empty? (query/ready tickets terminal-statuses)))))

  (testing "ready is sorted by priority asc, then :created desc"
    (let [tickets [(ticket "a" "open" [] :priority 3 :created "2026-01-01T00:00:00Z")
                   (ticket "b" "open" [] :priority 0 :created "2026-01-02T00:00:00Z")
                   (ticket "c" "open" [] :priority 0 :created "2026-01-03T00:00:00Z")
                   (ticket "d" "open" [] :priority 1 :created "2026-01-04T00:00:00Z")]
          ids (mapv #(get-in % [:frontmatter :id])
                    (query/ready tickets terminal-statuses))]
      ;; pri 0 group: c (newer) before b; then pri 1 d; then pri 3 a
      (is (= ["c" "b" "d" "a"] ids)))))

(deftest blocked-test
  (testing "open ticket with a non-terminal dep is blocked"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" [])]]
      (is (= ["a"] (mapv #(get-in % [:frontmatter :id])
                         (query/blocked tickets terminal-statuses))))))

  (testing "open ticket with all-terminal deps is NOT blocked"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "closed" [])]]
      (is (empty? (query/blocked tickets terminal-statuses)))))

  (testing "ticket with no deps is NOT blocked"
    (let [tickets [(ticket "a" "open" [])]]
      (is (empty? (query/blocked tickets terminal-statuses)))))

  (testing "closed tickets are excluded from blocked"
    (let [tickets [(ticket "a" "closed" ["b"])
                   (ticket "b" "open" [])]]
      (is (empty? (query/blocked tickets terminal-statuses)))))

  (testing "missing dep ref counts as blocking"
    (let [tickets [(ticket "a" "open" ["ghost"])]]
      (is (= ["a"] (mapv #(get-in % [:frontmatter :id])
                         (query/blocked tickets terminal-statuses)))))))

(deftest dep-tree-test
  (testing "root with no deps: single node, no children"
    (let [tickets [(ticket "a" "open" [])]
          tree    (query/dep-tree tickets "a" {})]
      (is (= "a" (:id tree)))
      (is (some? (:ticket tree)))
      (is (empty? (:children tree)))))

  (testing "root with one dep: recurses into the child"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" [])]
          tree    (query/dep-tree tickets "a" {})]
      (is (= "a" (:id tree)))
      (is (= ["b"] (mapv :id (:children tree))))
      (is (empty? (:children (first (:children tree)))))))

  (testing "diamond, default mode: second occurrence is marked :seen-before?"
    ;; a -> b -> d
    ;; a -> c -> d
    (let [tickets [(ticket "a" "open" ["b" "c"])
                   (ticket "b" "open" ["d"])
                   (ticket "c" "open" ["d"])
                   (ticket "d" "open" [])]
          tree    (query/dep-tree tickets "a" {})
          [b-node c-node] (:children tree)
          b-d     (first (:children b-node))
          c-d     (first (:children c-node))]
      (is (= "d" (:id b-d)))
      (is (false? (boolean (:seen-before? b-d)))
          "first occurrence of d expanded normally")
      (is (= "d" (:id c-d)))
      (is (true? (:seen-before? c-d))
          "second occurrence is deduped")))

  (testing "diamond with --full: both branches expanded fully, no seen-before"
    (let [tickets [(ticket "a" "open" ["b" "c"])
                   (ticket "b" "open" ["d"])
                   (ticket "c" "open" ["d"])
                   (ticket "d" "open" [])]
          tree    (query/dep-tree tickets "a" {:full? true})
          [b-node c-node] (:children tree)
          b-d     (first (:children b-node))
          c-d     (first (:children c-node))]
      (is (= "d" (:id b-d)))
      (is (= "d" (:id c-d)))
      (is (not (:seen-before? b-d)))
      (is (not (:seen-before? c-d)))))

  (testing "missing dep ref: child node has :missing? true"
    (let [tickets [(ticket "a" "open" ["ghost"])]
          tree    (query/dep-tree tickets "a" {})
          child   (first (:children tree))]
      (is (= "ghost" (:id child)))
      (is (true? (:missing? child)))))

  (testing "root id missing: returns a single :missing? node"
    (let [tickets [(ticket "a" "open" [])]
          tree    (query/dep-tree tickets "ghost" {})]
      (is (= "ghost" (:id tree)))
      (is (true? (:missing? tree)))))

  (testing "full mode terminates on cycles in the input graph"
    ;; a -> b -> a (cycle)
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" ["a"])]
          tree    (query/dep-tree tickets "a" {:full? true})]
      ;; smoke: must not infinite-loop. The cycle break must be marked
      ;; somehow (seen-before? on the second a).
      (let [b   (first (:children tree))
            a*  (first (:children b))]
        (is (= "a" (:id a*)))
        (is (true? (:seen-before? a*))
            "even in --full, a true cycle terminates with seen-before?")))))

(deftest broken-refs-test
  (testing "ticket with no :deps and no :parent: empty result"
    (let [t      (ticket "a" "open" [])
          others [t (ticket "b" "open" [])]]
      (is (empty? (query/broken-refs t others)))))

  (testing "ticket with :deps all resolvable: empty result"
    (let [t      (ticket "a" "open" ["b" "c"])
          others [t (ticket "b" "open" []) (ticket "c" "open" [])]]
      (is (empty? (query/broken-refs t others)))))

  (testing "ticket with one missing :deps entry: returns one :deps broken ref"
    (let [t      (ticket "a" "open" ["b" "ghost"])
          others [t (ticket "b" "open" [])]
          refs   (query/broken-refs t others)]
      (is (= 1 (count refs)))
      (is (= {:kind :deps :id "ghost"} (first refs)))))

  (testing "ticket with missing :parent: returns one :parent broken ref"
    (let [t      {:frontmatter {:id "a" :status "open" :parent "ghost"}}
          others [t]
          refs   (query/broken-refs t others)]
      (is (= [{:kind :parent :id "ghost"}] refs))))

  (testing "ticket with both broken :deps and broken :parent"
    (let [t      {:frontmatter {:id "a" :status "open"
                                :deps ["x" "y"] :parent "z"}}
          others [t]
          refs   (set (query/broken-refs t others))]
      (is (contains? refs {:kind :deps :id "x"}))
      (is (contains? refs {:kind :deps :id "y"}))
      (is (contains? refs {:kind :parent :id "z"})))))
