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
                                  :title "Fully populated ticket"
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
                    :body "Description.\n"}
          rendered (ticket/render original)
          re-parsed (ticket/parse rendered)]
      (is (= (:body original) (:body re-parsed)))
      (doseq [k [:id :title :status :type :priority :assignee :tags :deps :links :created :updated :mode]]
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

(deftest append-note-test
  (testing "appends a timestamped note block under an existing ## Notes section"
    (let [body  "# Title\n\n## Notes\n"
          out   (ticket/append-note body "2026-04-28T10:00:00Z" "First note.")]
      (is (str/includes? out "## Notes"))
      (is (str/includes? out "**2026-04-28T10:00:00Z**"))
      (is (str/includes? out "First note."))
      (is (str/ends-with? out "\n"))))

  (testing "creates ## Notes section at end of body when missing"
    (let [body "# Title\n\n## Description\n\nSome text.\n"
          out  (ticket/append-note body "2026-04-28T10:00:00Z" "First note.")]
      (is (str/includes? out "## Notes"))
      (is (str/includes? out "**2026-04-28T10:00:00Z**"))
      (is (str/includes? out "First note."))
      (is (str/includes? out "## Description"))
      (is (str/includes? out "Some text."))
      ;; original section preserved before Notes
      (is (< (str/index-of out "## Description") (str/index-of out "## Notes")))))

  (testing "appends multiple notes chronologically, preserving prior notes"
    (let [body "# Title\n\n## Notes\n"
          one  (ticket/append-note body "2026-04-28T10:00:00Z" "First.")
          two  (ticket/append-note one  "2026-04-28T11:00:00Z" "Second.")]
      (is (str/includes? two "**2026-04-28T10:00:00Z**"))
      (is (str/includes? two "**2026-04-28T11:00:00Z**"))
      (is (str/includes? two "First."))
      (is (str/includes? two "Second."))
      (is (< (str/index-of two "10:00:00") (str/index-of two "11:00:00"))
          "older note comes before the newer one")))

  (testing "leaves prior body content above Notes intact"
    (let [body "# Title\n\n## Description\n\nDesc.\n\n## Notes\n\n**2026-01-01T00:00:00Z**\n\nOlder.\n"
          out  (ticket/append-note body "2026-04-28T10:00:00Z" "Newer.")]
      (is (str/includes? out "Desc."))
      (is (str/includes? out "Older."))
      (is (str/includes? out "Newer."))
      (is (< (str/index-of out "Older.") (str/index-of out "Newer.")))))

  (testing "note content with internal newlines is preserved verbatim"
    (let [body "# Title\n\n## Notes\n"
          out  (ticket/append-note body "2026-04-28T10:00:00Z" "Line one.\nLine two.")]
      (is (str/includes? out "Line one.\nLine two."))))

  (testing "## NotesAndStuff is not treated as a Notes heading"
    ;; A heading whose label starts with `Notes` but continues with more
    ;; text must not be a false-positive. The append should create a
    ;; brand-new `## Notes` section instead of reusing the lookalike.
    (let [body "# Title\n\n## NotesAndStuff\n\nUnrelated.\n"
          out  (ticket/append-note body "2026-04-28T10:00:00Z" "New.")]
      (is (str/includes? out "## NotesAndStuff"))
      (is (str/includes? out "## Notes\n\n"))
      (is (< (str/index-of out "## NotesAndStuff")
             (str/index-of out "\n## Notes\n"))
          "the lookalike heading is preserved; a fresh ## Notes section is appended after it")))

  (testing "new note lands inside ## Notes even when later sections follow it"
    ;; A user may hand-edit a ticket so that some other `## ...` section
    ;; comes after Notes. The new note must still land inside Notes,
    ;; not after the trailing section.
    (let [body "# Title\n\n## Notes\n\nA.\n\n## Other\n\nLast.\n"
          out  (ticket/append-note body "2026-04-28T10:00:00Z" "NEW.")
          notes-pos (str/index-of out "## Notes")
          new-pos   (str/index-of out "NEW.")
          other-pos (str/index-of out "## Other")
          last-pos  (str/index-of out "Last.")]
      (is (and notes-pos new-pos other-pos last-pos)
          "all anchors should still be present")
      (is (< notes-pos new-pos other-pos last-pos)
          "order: Notes heading → new note → ## Other → Last."))))
