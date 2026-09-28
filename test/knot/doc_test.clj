(ns knot.doc-test
  "Pure document-module tests, and deliberately free of every global-mutation
   form the test runner scans for. That scan is a plain substring match over
   the whole file, so naming those forms here — even inside this docstring —
   would move the namespace into the serial phase for the rest of the
   project's life. See script/knot/test_runner.clj for the list."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.doc :as doc]
            [knot.ticket :as ticket]))

(def ^:private adversarial-body
  "A body exercising every shape the frontmatter envelope could mangle: a
   line that looks like a fence, a heading knot does not own, and trailing
   whitespace."
  (str "Leading text.\n\n"
       "---\n\n"
       "## A heading that is not a section knot owns\n\n"
       "Body text.   \n"))

(deftest document-round-trip-test
  (testing "an adversarial body survives render then parse byte-for-byte"
    (let [d {:frontmatter {:id      "kno-d01abc0000"
                           :ticket  "kno-01xyz0000"
                           :title   "T"
                           :type    "spec"
                           :created "2026-01-01T00:00:00Z"
                           :updated "2026-01-01T00:00:00Z"}
             :body adversarial-body}
          round-tripped (ticket/parse (ticket/render d))]
      (is (= adversarial-body (:body round-tripped)))
      (is (= (:frontmatter d) (:frontmatter round-tripped))))))

(deftest document-id-is-distinguishable-test
  (testing "a document id carries its owning ticket and cannot be mistaken for one"
    (let [did (doc/generate-id "kno-01abc0000")]
      (is (re-matches #"kno-01abc0000-d[0-9a-z]{4}" did))
      (is (str/starts-with? did "kno-01abc0000-")
          "the owner leads, so the id names its owner without a lookup")
      (is (not (re-matches #"kno-[0-9a-z]{12}" did))
          "a ticket id is prefix + 12 chars and has one hyphen; a document id has two")))
  (testing "every id in a burst still embeds the same owner"
    ;; Uniqueness is the store's job, not the generator's: the suffix is random
    ;; rather than a counter, so `save-new-doc!` regenerates on a collision and
    ;; `check` reports one already on disk. Asserting 50-of-50 distinct here
    ;; would be asserting a property the design deliberately does not promise.
    (let [ids (repeatedly 50 #(doc/generate-id "kno-01abc0000"))]
      (is (every? #(re-matches #"kno-01abc0000-d[0-9a-z]{4}" %) ids)))))

(deftest document-filename-test
  (testing "the document's own id leads the filename, never the owning ticket"
    (is (str/starts-with? (doc/filename "kno-d01abc0000" "A design note")
                          "kno-d01abc0000--"))
    (is (= "kno-d01abc0000--a-design-note.md"
           (doc/filename "kno-d01abc0000" "A design note"))))
  (testing "a blank title still yields a usable filename"
    (is (= "kno-d01abc0000--.md" (doc/filename "kno-d01abc0000" "")))))

(deftest document-id-of-test
  (testing "the leading id segment is recoverable from a filename"
    (is (= "kno-01abc0000-dwq0c" (doc/id-of "kno-01abc0000-dwq0c--a-design-note.md"))))
  (testing "a ticket filename is not a document filename"
    (is (nil? (doc/id-of "kno-01abc0000--some-ticket.md")))
    (is (nil? (doc/id-of "not-a-document.txt")))
    (is (nil? (doc/id-of nil)))))

(deftest document-valid-test
  (testing "every required field must be present and non-blank"
    (let [full {:frontmatter {:id "kno-d01a" :ticket "kno-01t" :title "T"
                              :type "spec" :created "x" :updated "y"}}]
      (is (doc/valid? full))
      (doseq [k doc/required-fields]
        (is (not (doc/valid? (update full :frontmatter dissoc k)))
            (str "missing " k " must invalidate"))
        (is (not (doc/valid? (assoc-in full [:frontmatter k] "  ")))
            (str "blank " k " must invalidate"))))))
