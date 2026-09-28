(ns knot.doc-codes-test
  "Guard against catalogue drift: every error code `main.clj` emits has a
   row in the error-code table of the skill source's json.md, and
   every check code `check.clj` emits has a row in its check-code table —
   in both directions, so a renamed code cannot leave a stale row behind.
   The guard pins the code *set* only; the per-command attribution column
   of the error table is prose and is not checked."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

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

(defn- documented-error-codes
  "Codes named in the error-code table. The 1-arity takes the document text
   so the guard can be driven against a synthetic catalogue; the 0-arity is
   what the real guard calls."
  ([] (documented-error-codes (slurp doc-path)))
  ([doc] (codes table-row-re (doc-section doc "## Error codes" "## Per-command"))))

(defn- documented-check-codes
  "Codes named in the check-code table. Same two arities, same reason."
  ([] (documented-check-codes (slurp doc-path)))
  ([doc] (codes table-row-re (doc-section doc "### `check` shape" "## Example"))))

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

(defn- run-guard
  "Run one of this namespace's own guard vars in isolation and return its
   `{:pass :fail :error}` counters. Output is captured so a deliberately
   failed guard does not print alarming text into a green run."
  [v]
  (binding [t/*report-counters*   (ref t/*initial-report-counters*)
            t/*testing-contexts* (list)
            t/*test-out*         (java.io.StringWriter.)]
    (t/test-var v)
    @t/*report-counters*))

(deftest catalogue-guard-fails-on-an-undocumented-code-test
  ;; AC-14c. The guard is the only thing standing between a new code and a
  ;; silently stale catalogue, so the guard itself has to be shown to fail.
  ;; This drives the real deftest var under substituted sources instead of
  ;; re-implementing its comparison: a re-implementation would certify this
  ;; helper and still pass if the real assertion were weakened or no-opped,
  ;; which is exactly the hole being closed.
  (testing "the check guard goes red when an emitted code has no catalogue row"
    (with-redefs [emitted-check-codes    (constantly (sorted-set "dep_cycle" "invented_doc_code"))
                  documented-check-codes (constantly (sorted-set "dep_cycle"))]
      (let [r (run-guard #'check-code-catalogue-matches-source-test)]
        (is (pos? (+ (:fail r) (:error r)))
            "the catalogue guard must fail when a code is emitted but undocumented"))))

  (testing "the check guard goes red when a catalogue row names no emitted code"
    (with-redefs [emitted-check-codes    (constantly (sorted-set "dep_cycle"))
                  documented-check-codes (constantly (sorted-set "dep_cycle" "retired_code"))]
      (let [r (run-guard #'check-code-catalogue-matches-source-test)]
        (is (pos? (+ (:fail r) (:error r)))
            "the reverse direction must fail too, or a renamed code leaves a stale row"))))

  (testing "the error guard goes red when an emitted code has no catalogue row"
    (with-redefs [emitted-error-codes    (constantly (sorted-set "not_found" "invented_doc_code"))
                  documented-error-codes (constantly (sorted-set "not_found"))]
      (let [r (run-guard #'error-code-catalogue-matches-source-test)]
        (is (pos? (+ (:fail r) (:error r)))))))

  (testing "and it passes when the two sides agree — the self-test is not vacuously red"
    (with-redefs [emitted-check-codes    (constantly (sorted-set "dep_cycle"))
                  documented-check-codes (constantly (sorted-set "dep_cycle"))]
      (let [r (run-guard #'check-code-catalogue-matches-source-test)]
        (is (zero? (+ (:fail r) (:error r)))
            "a guard that always fails would pass the three cases above for the wrong reason")))))

(deftest documented-code-readers-take-text-test
  (testing "the 1-arity reads the catalogue it is handed, not the file on disk"
    (let [synthetic (str "## Error codes\n\n| `made_up_code` | x | y | z |\n\n"
                         "## Per-command\n\n"
                         "### `check` shape\n\n| `made_up_check` | x | y |\n\n"
                         "## Example\n")]
      (is (= #{"made_up_code"} (set (documented-error-codes synthetic))))
      (is (= #{"made_up_check"} (set (documented-check-codes synthetic)))))))
