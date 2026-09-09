(ns knot.doc-codes-test
  "Guard against catalogue drift: every error code `main.clj` emits has a
   row in the error-code table of the skill source's json.md, and
   every check code `check.clj` emits has a row in its check-code table —
   in both directions, so a renamed code cannot leave a stale row behind.
   The guard pins the code *set* only; the per-command attribution column
   of the error table is prose and is not checked."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private doc-path "resources/knot/skill/references/json.md")

(defn- codes
  "Sorted set of the first capture group over every match of `re` in `text`."
  [re text]
  (into (sorted-set) (map second (re-seq re text))))

(defn- doc-section
  "The slice of `doc` from heading `from` up to heading `to`."
  [doc from to]
  (let [i (str/index-of doc from)
        j (str/index-of doc to i)]
    (assert (and i j) (str doc-path " lost the section between " from " and " to))
    (subs doc i j)))

(def ^:private table-row-re
  "First cell of a markdown table row holding a backticked code."
  #"(?m)^\| `([a-z_]+)`\s+\|")

(defn- emitted-error-codes
  "Error codes main.clj emits: `{:code \"…\"` envelope maps, plus the
   positional string handed to `emit-gate-failure!` / `info-emit-error!`
   after their `json?` argument."
  []
  (let [src (slurp "src/knot/main.clj")]
    (into (codes #":code\s+\"([a-z_]+)\"" src)
          (codes #"json\?\s+\"([a-z_]+)\"" src))))

(defn- emitted-check-codes
  "Check codes check.clj emits: `:code :kw` issue maps, plus the keyword
   handed to the `check-enum` validator builder."
  []
  (let [src (slurp "src/knot/check.clj")]
    (into (codes #":code\s+:([a-z_]+)" src)
          (codes #"\(check-enum\s+:([a-z_]+)" src))))

(defn- documented-error-codes []
  (codes table-row-re (doc-section (slurp doc-path) "## Error codes" "## Per-command")))

(defn- documented-check-codes []
  (codes table-row-re (doc-section (slurp doc-path) "### `check` shape" "## Example")))

(deftest error-code-catalogue-matches-source-test
  (let [src (emitted-error-codes)
        doc (documented-error-codes)]
    (testing "the extractor finds the emitted codes at all"
      (is (contains? src "not_found")))
    (testing "every emitted error code has a row in the catalogue"
      (is (empty? (remove doc src))
          (str "emitted but undocumented: " (pr-str (remove doc src)))))
    (testing "every catalogue row names an emitted error code"
      (is (empty? (remove src doc))
          (str "documented but never emitted: " (pr-str (remove src doc)))))))

(deftest check-code-catalogue-matches-source-test
  (let [src (emitted-check-codes)
        doc (documented-check-codes)]
    (testing "the extractor finds the emitted codes at all"
      (is (contains? src "dep_cycle")))
    (testing "every emitted check code has a row in the catalogue"
      (is (empty? (remove doc src))
          (str "emitted but undocumented: " (pr-str (remove doc src)))))
    (testing "every catalogue row names an emitted check code"
      (is (empty? (remove src doc))
          (str "documented but never emitted: " (pr-str (remove src doc)))))))
