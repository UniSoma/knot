(ns knot.acceptance-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.acceptance :as acceptance]))

(deftest render-section-test
  (testing "empty/nil acceptance returns empty string (no header)"
    (is (= "" (acceptance/render-section nil)))
    (is (= "" (acceptance/render-section []))))

  (testing "renders a `- [ ] / - [x]` checklist with leading blank-line separator"
    (is (= "\n## Acceptance Criteria\n\n- [ ] foo\n- [x] bar\n"
           (acceptance/render-section [{:title "foo" :done false}
                                       {:title "bar" :done true}])))))

(deftest from-titles-test
  (testing "lifts a vector of titles into structured entries with done:false"
    (is (= [{:title "a" :done false}
            {:title "b" :done false}]
           (acceptance/from-titles ["a" "b"]))))

  (testing "trims whitespace and drops blank/nil entries"
    (is (= [{:title "x" :done false}]
           (acceptance/from-titles ["  x  " "" nil "   "]))))

  (testing "returns nil when the seq has no usable titles"
    (is (nil? (acceptance/from-titles [])))
    (is (nil? (acceptance/from-titles nil)))
    (is (nil? (acceptance/from-titles ["" "  "])))))

(deftest flip-test
  (testing "flips the matching entry's :done state"
    (is (= [{:title "x" :done true}]
           (acceptance/flip [{:title "x" :done false}] "x" true)))
    (is (= [{:title "x" :done false}]
           (acceptance/flip [{:title "x" :done true}] "x" false))))

  (testing "leaves other entries untouched"
    (is (= [{:title "a" :done false}
            {:title "b" :done true}
            {:title "c" :done false}]
           (acceptance/flip [{:title "a" :done false}
                             {:title "b" :done false}
                             {:title "c" :done false}]
                            "b" true))))

  (testing "exact case-sensitive match (no fuzzy matching)"
    (is (nil? (acceptance/flip [{:title "Foo" :done false}] "foo" true))))

  (testing "no match returns nil"
    (is (nil? (acceptance/flip [{:title "x" :done false}] "ghost" true)))
    (is (nil? (acceptance/flip nil "anything" true)))
    (is (nil? (acceptance/flip [] "anything" true)))))

(deftest parse-body-section-test
  (testing "returns nil when the body has no `## Acceptance Criteria` section"
    (is (nil? (acceptance/parse-body-section "")))
    (is (nil? (acceptance/parse-body-section "## Description\n\nfoo\n"))))

  (testing "extracts `- [ ]` / `- [x]` items as structured entries"
    (let [body "## Description\n\nDesc.\n\n## Acceptance Criteria\n\n- [ ] first\n- [x] second\n- [ ] third\n"]
      (is (= [{:title "first"  :done false}
              {:title "second" :done true}
              {:title "third"  :done false}]
             (acceptance/parse-body-section body)))))

  (testing "handles uppercase X and trims surrounding whitespace from titles"
    (let [body "## Acceptance Criteria\n\n- [X] DONE\n- [ ]   spaced  \n"]
      (is (= [{:title "DONE"   :done true}
              {:title "spaced" :done false}]
             (acceptance/parse-body-section body)))))

  (testing "lifts plain `- title` bullets as undone criteria"
    (let [body "## Acceptance Criteria\n\n- A real criterion in plain-bullet form\n- Another one\n"]
      (is (= [{:title "A real criterion in plain-bullet form" :done false}
              {:title "Another one"                            :done false}]
             (acceptance/parse-body-section body)))))

  (testing "ignores non-bullet prose lines inside the AC section"
    (let [body "## Acceptance Criteria\n\n- [ ] valid checkbox\nstray prose line\nNo bullet here\n- [x] also valid\n"]
      (is (= [{:title "valid checkbox" :done false}
              {:title "also valid"     :done true}]
             (acceptance/parse-body-section body)))))

  (testing "stops at the next `## ` section heading"
    (let [body "## Acceptance Criteria\n\n- [ ] in scope\n\n## Notes\n\n- [ ] out of scope\n"]
      (is (= [{:title "in scope" :done false}]
             (acceptance/parse-body-section body)))))

  (testing "AC section with no checkbox items returns nil (nothing to migrate)"
    (let [body "## Acceptance Criteria\n\n(none yet)\n"]
      (is (nil? (acceptance/parse-body-section body))))))

(deftest strip-body-section-test
  (testing "removes the `## Acceptance Criteria` section in place"
    (let [body "## Description\n\nDesc.\n\n## Acceptance Criteria\n\n- [ ] item\n\n## Notes\n\nNote.\n"
          out  (acceptance/strip-body-section body)]
      (is (str/includes? out "## Description"))
      (is (str/includes? out "## Notes"))
      (is (not (str/includes? out "## Acceptance Criteria")))
      (is (not (str/includes? out "- [ ] item")))))

  (testing "no-op when the body has no AC section"
    (let [body "## Description\n\nDesc.\n"]
      (is (= body (acceptance/strip-body-section body)))))

  (testing "AC section is the only content -> body becomes empty-ish"
    (let [body "## Acceptance Criteria\n\n- [ ] item\n"
          out  (acceptance/strip-body-section body)]
      (is (not (str/includes? out "## Acceptance Criteria")))
      (is (not (str/includes? out "item")))))

  (testing "is idempotent: stripping twice yields the same result"
    (let [body "## Description\n\nD.\n\n## Acceptance Criteria\n\n- [ ] x\n"
          once (acceptance/strip-body-section body)
          twice (acceptance/strip-body-section once)]
      (is (= once twice)))))

(deftest migrate-ticket-test
  (testing "lifts body AC into frontmatter and strips the section"
    (let [ticket {:frontmatter {:id "kno-A" :title "T"}
                  :body        "## Description\n\nD.\n\n## Acceptance Criteria\n\n- [ ] foo\n- [x] bar\n"}
          out    (acceptance/migrate-ticket ticket)]
      (is (= [{:title "foo" :done false}
              {:title "bar" :done true}]
             (get-in out [:frontmatter :acceptance])))
      (is (not (str/includes? (:body out) "## Acceptance Criteria")))
      (is (str/includes? (:body out) "## Description"))))

  (testing "returns the input unchanged when there is no AC section to migrate (idempotent)"
    (let [ticket {:frontmatter {:id "kno-A" :title "T"}
                  :body        "## Description\n\nD.\n"}]
      (is (= ticket (acceptance/migrate-ticket ticket)))))

  (testing "already-migrated ticket (frontmatter has :acceptance, body has no section) is unchanged"
    (let [ticket {:frontmatter {:id "kno-A" :title "T"
                                :acceptance [{:title "x" :done true}]}
                  :body        "## Description\n\nD.\n"}]
      (is (= ticket (acceptance/migrate-ticket ticket))))))
