(ns knot.ticket-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.ticket :as ticket]))

(def crockford-base32-lower
  "Crockford base32, lowercase, with I L O U excluded."
  "0123456789abcdefghjkmnpqrstvwxyz")

(deftest generate-id-test
  (testing "id has the form <prefix>-<12 lowercase Crockford-base32 chars>"
    (let [id (ticket/generate-id "kno")
          alphabet (set crockford-base32-lower)]
      (is (re-matches #"kno-[0-9a-z]{12}" id))
      (is (every? alphabet (subs id 4)))
      (is (= 16 (count id)))))
  (testing "the alphabet excludes I, L, O, U (Crockford property)"
    (let [ids   (repeatedly 50 #(ticket/generate-id "p"))
          chars (set (mapcat #(seq (subs % 2)) ids))]
      (is (not-any? chars [\i \l \o \u]))))
  (testing "ids generated across millisecond boundaries are unique and sortable"
    (let [a (ticket/generate-id "p")
          _ (Thread/sleep 2)
          b (ticket/generate-id "p")
          _ (Thread/sleep 2)
          c (ticket/generate-id "p")]
      (is (= 3 (count (distinct [a b c]))))
      (is (neg? (compare a b)))
      (is (neg? (compare b c)))))
  (testing "the random suffix is not constant across calls"
    (let [last-two #(subs % (- (count %) 2))
          randoms  (set (map last-two (repeatedly 50 #(ticket/generate-id "p"))))]
      (is (> (count randoms) 1)
          "with 50 samples, the 2-char random suffix should produce more than one distinct value"))))

(deftest parse-test
  (testing "parses a file into :frontmatter and :body"
    (let [content "---\nid: kno-01abcdef0123\nstatus: open\n---\n\n# Title\n\nBody text.\n"
          {:keys [frontmatter body]} (ticket/parse content)]
      (is (= "kno-01abcdef0123" (:id frontmatter)))
      (is (= "open" (:status frontmatter)))
      (is (= "# Title\n\nBody text.\n" body)))))

(deftest render-test
  (testing "renders a ticket map back into a frontmatter+body string"
    (let [m {:frontmatter {:id "kno-01abcdef0123" :status "open"}
             :body "# Title\n\nBody text.\n"}
          s (ticket/render m)]
      (is (str/starts-with? s "---\n"))
      (is (str/includes? s "id: kno-01abcdef0123"))
      (is (str/includes? s "status: open"))
      (is (str/ends-with? s "# Title\n\nBody text.\n")))))

(deftest round-trip-preserves-unknown-keys-test
  (testing "unknown frontmatter keys survive parse → render → parse"
    (let [content (str "---\n"
                       "id: kno-01abcdef0123\n"
                       "status: open\n"
                       "experimental_field: hello\n"
                       "another_unknown: 42\n"
                       "---\n\n"
                       "# Title\n\n"
                       "Body text.\n")
          parsed (ticket/parse content)
          re-parsed (ticket/parse (ticket/render parsed))]
      (is (= "hello" (get-in re-parsed [:frontmatter :experimental_field])))
      (is (= 42 (get-in re-parsed [:frontmatter :another_unknown])))
      (is (= (:body parsed) (:body re-parsed))))))

(deftest round-trip-preserves-known-keys-test
  (testing "round-tripping a fully populated ticket preserves all keys"
    (let [original {:frontmatter {:id "kno-01abcdef0123"
                                  :status "open"
                                  :type "task"
                                  :priority 2
                                  :assignee "alice"
                                  :tags ["a" "b"]
                                  :deps ["kno-other"]
                                  :links []
                                  :created "2026-04-28T10:00:00Z"
                                  :updated "2026-04-28T10:00:00Z"
                                  :mode "hitl"}
                    :body "# Title\n\nDescription.\n"}
          rendered (ticket/render original)
          re-parsed (ticket/parse rendered)]
      (is (= (:body original) (:body re-parsed)))
      (doseq [k [:id :status :type :priority :assignee :tags :deps :links :created :updated :mode]]
        (is (= (get-in original [:frontmatter k])
               (get-in re-parsed [:frontmatter k]))
            (str "key " k " round-tripped"))))))

(deftest derive-slug-test
  (testing "basic title slugifies with hyphens between words"
    (is (= "fix-login-bug" (ticket/derive-slug "Fix login bug"))))
  (testing "punctuation maps to hyphens"
    (is (= "hello-world" (ticket/derive-slug "Hello, World!"))))
  (testing "runs of non-alphanumerics collapse to a single hyphen"
    (is (= "foo-bar" (ticket/derive-slug "foo---bar")))
    (is (= "a-b" (ticket/derive-slug "a !! b"))))
  (testing "leading and trailing non-alphanumerics are trimmed"
    (is (= "trim-me" (ticket/derive-slug "  trim me  ")))
    (is (= "edge" (ticket/derive-slug "---edge---"))))
  (testing "empty or all-stripped titles produce empty string"
    (is (= "" (ticket/derive-slug "")))
    (is (= "" (ticket/derive-slug "   ")))
    (is (= "" (ticket/derive-slug "!@#$%")))
    (is (= "" (ticket/derive-slug nil))))
  (testing "Unicode characters are stripped, not transliterated"
    (is (= "caf" (ticket/derive-slug "café")))
    (is (= "rsum" (ticket/derive-slug "résumé"))))
  (testing "numbers are preserved"
    (is (= "issue-123" (ticket/derive-slug "Issue 123"))))
  (testing "long titles truncate at the last hyphen ≤ 50"
    (let [title "this is a fairly long title that exceeds fifty characters in length"
          slug  (ticket/derive-slug title)]
      (is (<= (count slug) 50))
      (is (not (str/ends-with? slug "-")))
      (is (str/starts-with? slug "this-is-a-fairly-long-title")))))

(deftest derive-prefix-test
  (testing "multi-segment project name uses first letter of each segment"
    (is (= "mp" (ticket/derive-prefix "my-project"))))
  (testing "single-segment short name falls back to first 3 chars"
    (is (= "kno" (ticket/derive-prefix "knot"))))
  (testing "underscore acts as a segment separator"
    (is (= "abc" (ticket/derive-prefix "a_b_c"))))
  (testing "mixed dash/underscore separators"
    (is (= "fbb" (ticket/derive-prefix "foo-bar_baz"))))
  (testing "uppercase letters are lowercased"
    (is (= "hw" (ticket/derive-prefix "Hello-World"))))
  (testing "names shorter than 3 chars stay as-is"
    (is (= "ab" (ticket/derive-prefix "ab")))
    (is (= "x" (ticket/derive-prefix "x"))))
  (testing "spaces are treated as segment separators"
    (is (= "mp" (ticket/derive-prefix "My Project"))))
  (testing "leading/trailing whitespace yields a clean single-segment prefix"
    (is (= "foo" (ticket/derive-prefix " foo "))))
  (testing "empty string falls back to the literal default"
    (is (= "knot" (ticket/derive-prefix ""))))
  (testing "whitespace-only input falls back to the literal default"
    (is (= "knot" (ticket/derive-prefix "   "))))
  (testing "pure punctuation falls back to the literal default"
    (is (= "knot" (ticket/derive-prefix "!!!"))))
  (testing "non-ASCII chars act as separators (no transliteration)"
    (is (= "ca" (ticket/derive-prefix "café-app"))))
  (testing "result never contains hyphens or whitespace"
    (doseq [in ["My Project" " foo " "Hello-World" "a-b-c-d"]]
      (let [out (ticket/derive-prefix in)]
        (is (re-matches #"[a-z0-9]+" out)
            (str "expected [a-z0-9]+ for input " (pr-str in) ", got " (pr-str out)))))))
