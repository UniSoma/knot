(ns knot.output-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.output :as output]))

(deftest show-text-test
  (testing "renders frontmatter and body for the show command"
    (let [ticket {:frontmatter {:id "kno-01abc"
                                :status "open"
                                :type "task"
                                :priority 2}
                  :body "# Fix login\n\nDescription text.\n"}
          s (output/show-text ticket)]
      (is (str/includes? s "id: kno-01abc"))
      (is (str/includes? s "status: open"))
      (is (str/includes? s "type: task"))
      (is (str/includes? s "# Fix login"))
      (is (str/includes? s "Description text.")))))

(defn- mk-resolved
  "Test helper: build a `{:id ... :ticket ...}` resolved-ref entry whose
   embedded ticket has an H1 title for `extract-title` to recover."
  [id title]
  {:id id :ticket {:frontmatter {:id id :status "open"}
                   :body (str "# " title "\n")}})

(defn- mk-missing
  [id]
  {:id id :missing? true})

(deftest show-text-inverses-test
  (testing "all four sections appear in order when populated"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"}
                  :body "# Alpha\n\nBody text.\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [(mk-resolved "kno-C" "Gamma")]
                    :children [(mk-resolved "kno-D" "Delta")]
                    :linked   [(mk-resolved "kno-E" "Epsilon")]}
          s (output/show-text ticket inverses)
          b-i (str/index-of s "## Blockers")
          bg-i (str/index-of s "## Blocking")
          c-i (str/index-of s "## Children")
          l-i (str/index-of s "## Linked")]
      (is (every? some? [b-i bg-i c-i l-i]))
      (is (< b-i bg-i c-i l-i)
          "sections should appear in the canonical order")))

  (testing "inverse sections appear AFTER the body"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"}
                  :body "# Alpha\n\nBody text.\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)
          body-i (str/index-of s "Body text.")
          sec-i  (str/index-of s "## Blockers")]
      (is (< body-i sec-i)
          "computed sections come after the rendered body")))

  (testing "empty sections are omitted entirely (no header line)"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (str/includes? s "## Blockers"))
      (is (not (str/includes? s "## Blocking")))
      (is (not (str/includes? s "## Children")))
      (is (not (str/includes? s "## Linked")))))

  (testing "all four sections empty: output has no computed-section headers"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [] :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (not (str/includes? s "## Blockers")))
      (is (not (str/includes? s "## Blocking")))
      (is (not (str/includes? s "## Children")))
      (is (not (str/includes? s "## Linked")))))

  (testing "each entry renders as `- <id>  <title>` (resolved)"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")
                               (mk-resolved "kno-C" "Gamma")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (str/includes? s "- kno-B  Beta"))
      (is (str/includes? s "- kno-C  Gamma"))))

  (testing "missing refs render with `[missing]` marker"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [(mk-missing "kno-ghost")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (str/includes? s "- kno-ghost  [missing]"))))

  (testing "single-arity show-text (no inverses) still works"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          s (output/show-text ticket)]
      (is (str/includes? s "# Alpha"))
      (is (not (str/includes? s "## Blockers"))))))

(deftest color-enabled-test
  (testing "TTY with no overrides — color enabled"
    (is (true? (output/color-enabled? {:tty? true
                                       :no-color? false
                                       :no-color-env nil}))))
  (testing "non-TTY (piped) — color disabled even when no overrides set"
    (is (false? (output/color-enabled? {:tty? false
                                        :no-color? false
                                        :no-color-env nil}))))
  (testing "--no-color flag overrides TTY — color disabled"
    (is (false? (output/color-enabled? {:tty? true
                                        :no-color? true
                                        :no-color-env nil}))))
  (testing "NO_COLOR=1 disables color (any non-empty value)"
    (is (false? (output/color-enabled? {:tty? true
                                        :no-color? false
                                        :no-color-env "1"})))
    (is (false? (output/color-enabled? {:tty? true
                                        :no-color? false
                                        :no-color-env "anything"}))))
  (testing "NO_COLOR=\"\" (empty string) does NOT disable color (no-color.org spec)"
    (is (true? (output/color-enabled? {:tty? true
                                       :no-color? false
                                       :no-color-env ""})))))

(deftest terminal-width-test
  (testing "$COLUMNS, when a positive integer, wins over every other source"
    (with-redefs [knot.output/env-cols (constantly 137)
                  knot.output/stty-cols (constantly 999)]
      (is (= 137 (output/terminal-width)))))
  (testing "stty fallback is used when $COLUMNS is unset"
    (with-redefs [knot.output/env-cols (constantly nil)
                  knot.output/stty-cols (constantly 200)]
      (is (= 200 (output/terminal-width)))))
  (testing "default is used when neither probe yields a value"
    (with-redefs [knot.output/env-cols (constantly nil)
                  knot.output/stty-cols (constantly nil)]
      (is (= 80 (output/terminal-width)))
      (is (= 100 (output/terminal-width 100)))))
  (testing "the function never throws and always returns a positive int"
    (let [w (output/terminal-width)]
      (is (integer? w))
      (is (pos? w)))))

(defn- strip-ansi [s]
  (str/replace s #"\[[0-9;]*m" ""))

(def sample-ls-tickets
  [{:frontmatter {:id "kno-01abcd0001"
                  :status "open"
                  :priority 2
                  :mode "afk"
                  :type "task"
                  :assignee "alice"}
    :body "# Fix login bug\n"}
   {:frontmatter {:id "kno-01abcd0002"
                  :status "in_progress"
                  :priority 0
                  :mode "hitl"
                  :type "bug"
                  :assignee "bob"}
    :body "# Critical pager outage during peak hours please address\n"}])

(deftest ls-table-headers-test
  (testing "header row contains the expected column names in order"
    (let [out (output/ls-table sample-ls-tickets {:color? false :tty? false})
          first-line (first (str/split-lines out))]
      (is (re-find #"ID" first-line))
      (is (re-find #"STATUS" first-line))
      (is (re-find #"PRI" first-line))
      (is (re-find #"MODE" first-line))
      (is (re-find #"TYPE" first-line))
      (is (re-find #"ASSIGNEE" first-line))
      (is (re-find #"TITLE" first-line))
      (let [order (re-seq #"ID|STATUS|PRI|MODE|TYPE|ASSIGNEE|TITLE" first-line)]
        (is (= ["ID" "STATUS" "PRI" "MODE" "TYPE" "ASSIGNEE" "TITLE"] order))))))

(deftest ls-table-renders-data-test
  (testing "data rows include the ticket id, status, priority, mode, type, assignee, title"
    (let [out (output/ls-table sample-ls-tickets {:color? false :tty? false})]
      (is (str/includes? out "kno-01abcd0001"))
      (is (str/includes? out "open"))
      (is (str/includes? out "afk"))
      (is (str/includes? out "task"))
      (is (str/includes? out "alice"))
      (is (str/includes? out "Fix login bug"))
      (is (str/includes? out "in_progress"))
      (is (str/includes? out "bob")))))

(deftest ls-table-title-piped-test
  (testing "when piped (tty? false), TITLE is full-width — long titles are not truncated"
    (let [long-title "Critical pager outage during peak hours please address"
          out (output/ls-table sample-ls-tickets {:color? false :tty? false})]
      (is (str/includes? out long-title)))))

(deftest ls-table-title-tty-truncated-test
  (testing "when tty? true with a constrained width, long titles are truncated"
    (let [out (output/ls-table sample-ls-tickets
                               {:color? false :tty? true :width 60})
          plain (strip-ansi out)
          long-title "Critical pager outage during peak hours please address"]
      (is (not (str/includes? plain long-title))
          "with width=60 the long title should not appear in full")
      (doseq [line (str/split-lines plain)]
        (is (<= (count line) 60)
            (str "row exceeds the requested width: " (pr-str line)))))))

(deftest ls-table-pri-right-aligned-test
  (testing "PRI column is right-aligned (whitespace-padded on the left within the column slot)"
    (let [tickets [{:frontmatter {:id "x" :status "open" :priority 0
                                  :mode "hitl" :type "task" :assignee "a"}
                    :body "# T\n"}
                   {:frontmatter {:id "y" :status "open" :priority 4
                                  :mode "hitl" :type "task" :assignee "a"}
                    :body "# T\n"}]
          out (output/ls-table tickets {:color? false :tty? false})
          lines (str/split-lines out)
          header (first lines)
          pri-idx (str/index-of header "PRI")
          pri-right (+ pri-idx (count "PRI"))]
      ;; both data rows must place the digit at the rightmost column of the
      ;; PRI slot (i.e. immediately before the next column gap)
      (is (some? pri-idx))
      (doseq [line (rest lines)
              :when (not (str/blank? line))]
        (let [ch (.charAt line (dec pri-right))]
          (is (or (= \0 ch) (= \4 ch))
              (str "expected priority digit at column " (dec pri-right)
                   " but got " (pr-str ch) " in line " (pr-str line))))))))

(deftest ls-table-color-suppressed-test
  (testing "with color? false, output contains no ANSI escape codes"
    (let [out (output/ls-table sample-ls-tickets {:color? false :tty? true})]
      (is (not (re-find #"\[" out))
          "expected no ANSI escape sequences when color is disabled"))))

(deftest ls-table-color-applied-test
  (testing "with color? true, ANSI escape codes appear in the output"
    (let [out (output/ls-table sample-ls-tickets {:color? true :tty? true :width 200})]
      (is (re-find #"\[" out)))))

(deftest show-json-test
  (testing "renders a bare JSON object (not wrapped in an envelope)"
    (let [ticket {:frontmatter {:id "kno-01abc"
                                :status "open"
                                :type "task"
                                :priority 2
                                :external_refs ["JIRA-1"]}
                  :body "# Title\n\nBody.\n"}
          out (output/show-json ticket)]
      (is (str/starts-with? out "{"))
      (is (str/ends-with? (str/trimr out) "}"))))
  (testing "uses snake_case keys (e.g. external_refs preserved)"
    (let [ticket {:frontmatter {:id "kno-01abc"
                                :external_refs ["JIRA-1" "JIRA-2"]}
                  :body ""}
          out (output/show-json ticket)]
      (is (str/includes? out "\"external_refs\""))
      (is (not (str/includes? out "external-refs")))))
  (testing "frontmatter fields appear at the top level of the object"
    (let [ticket {:frontmatter {:id "kno-01abc" :status "open" :priority 2}
                  :body "B"}
          out (output/show-json ticket)]
      (is (str/includes? out "\"id\":\"kno-01abc\""))
      (is (str/includes? out "\"status\":\"open\""))
      (is (str/includes? out "\"priority\":2"))))
  (testing "body is included as a top-level field"
    (let [ticket {:frontmatter {:id "kno-01abc"} :body "# Title\n"}
          out (output/show-json ticket)]
      (is (str/includes? out "\"body\""))
      (is (str/includes? out "Title")))))

(deftest show-json-inverses-test
  (testing "show-json with inverses adds blockers/blocking/children/linked arrays"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [(mk-resolved "kno-C" "Gamma")]
                    :children [(mk-resolved "kno-D" "Delta")]
                    :linked   [(mk-resolved "kno-E" "Epsilon")]}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"blockers\""))
      (is (str/includes? out "\"blocking\""))
      (is (str/includes? out "\"children\""))
      (is (str/includes? out "\"linked\""))))

  (testing "each inverse entry has id/title/status keys (resolved)"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"id\":\"kno-B\""))
      (is (str/includes? out "\"title\":\"Beta\""))
      (is (str/includes? out "\"status\":\"open\""))))

  (testing "missing entries serialize with `missing:true` and no title/status"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [(mk-missing "kno-ghost")]
                    :blocking [] :children [] :linked []}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"id\":\"kno-ghost\""))
      (is (str/includes? out "\"missing\":true"))))

  (testing "empty sections serialize as []"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          inverses {:blockers [] :blocking [] :children [] :linked []}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"blockers\":[]"))
      (is (str/includes? out "\"blocking\":[]"))
      (is (str/includes? out "\"children\":[]"))
      (is (str/includes? out "\"linked\":[]"))))

  (testing "single-arity show-json (no inverses) does not add inverse fields"
    (let [ticket {:frontmatter {:id "kno-A" :status "open"} :body "# Alpha\n"}
          out (output/show-json ticket)]
      (is (not (str/includes? out "\"blockers\"")))
      (is (not (str/includes? out "\"blocking\"")))
      (is (not (str/includes? out "\"children\"")))
      (is (not (str/includes? out "\"linked\""))))))

(deftest ls-json-test
  (testing "renders a bare JSON array (not an envelope)"
    (let [tickets [{:frontmatter {:id "a" :status "open"} :body ""}
                   {:frontmatter {:id "b" :status "in_progress"} :body ""}]
          out (output/ls-json tickets)]
      (is (str/starts-with? out "["))
      (is (str/ends-with? (str/trimr out) "]"))))
  (testing "each entry is an object with the ticket's frontmatter fields"
    (let [tickets [{:frontmatter {:id "a" :status "open"} :body ""}
                   {:frontmatter {:id "b" :status "in_progress"} :body ""}]
          out (output/ls-json tickets)]
      (is (str/includes? out "\"id\":\"a\""))
      (is (str/includes? out "\"id\":\"b\""))
      (is (str/includes? out "\"status\":\"open\""))
      (is (str/includes? out "\"status\":\"in_progress\""))))
  (testing "snake_case keys preserved (external_refs)"
    (let [tickets [{:frontmatter {:id "a" :external_refs ["JIRA-1"]} :body ""}]
          out (output/ls-json tickets)]
      (is (str/includes? out "\"external_refs\""))))
  (testing "an empty list renders as []"
    (is (= "[]" (str/trim (output/ls-json []))))))

(deftest colorize-test
  (testing "with color? false, returns the text unchanged"
    (is (= "hello" (output/colorize false [:cyan] "hello")))
    (is (= "hello" (output/colorize false [:red :bold] "hello"))))
  (testing "with color? true, wraps in ANSI escape codes"
    (let [s (output/colorize true [:cyan] "hello")]
      (is (str/starts-with? s "["))
      (is (str/ends-with? s "[0m"))
      (is (str/includes? s "hello"))
      (is (str/includes? s "36"))))
  (testing "with color? true and multiple codes, all codes appear in the SGR sequence"
    (let [s (output/colorize true [:red :bold] "x")]
      (is (str/includes? s "31"))
      (is (str/includes? s "1"))
      (is (str/ends-with? s "[0m"))))
  (testing "empty code list returns text unchanged regardless of color?"
    (is (= "x" (output/colorize true [] "x")))
    (is (= "x" (output/colorize false [] "x")))))

(defn- node
  "Test helper: build a dep-tree node mirroring `knot.query/dep-tree`'s
   shape. Children are nested; pass opts in `extras` like {:missing? true}."
  [id title & {:keys [children seen-before? missing?]}]
  (cond-> {:id id}
    missing?       (assoc :missing? true)
    (not missing?) (assoc :ticket {:frontmatter {:id id :status "open"}
                                   :body (str "# " title "\n")})
    seen-before?   (assoc :seen-before? true)
    children       (assoc :children children)))

(deftest dep-tree-text-root-only-test
  (testing "root node with no children renders as a single id+title line"
    (let [tree (node "kno-A" "Alpha")
          out  (output/dep-tree-text tree)]
      (is (= "kno-A  Alpha" (str/trim out))))))

(deftest dep-tree-text-children-test
  (testing "single child uses a └── connector"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-B" "Beta")])
          lines (str/split-lines (output/dep-tree-text tree))]
      (is (= 2 (count lines)))
      (is (= "kno-A  Alpha" (first lines)))
      (is (str/starts-with? (second lines) "└── "))
      (is (str/includes? (second lines) "kno-B"))
      (is (str/includes? (second lines) "Beta"))))

  (testing "multiple children use ├── for non-last and └── for last"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-B" "Beta")
                                (node "kno-C" "Gamma")])
          lines (str/split-lines (output/dep-tree-text tree))]
      (is (= 3 (count lines)))
      (is (str/starts-with? (nth lines 1) "├── "))
      (is (str/starts-with? (nth lines 2) "└── ")))))

(deftest dep-tree-text-nested-prefix-test
  (testing "a non-last branch's grandchildren get a │ continuation prefix"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-B" "Beta"
                                      :children [(node "kno-D" "Delta")])
                                (node "kno-C" "Gamma")])
          lines (str/split-lines (output/dep-tree-text tree))]
      ;; Expected:
      ;; kno-A  Alpha
      ;; ├── kno-B  Beta
      ;; │   └── kno-D  Delta
      ;; └── kno-C  Gamma
      (is (= 4 (count lines)))
      (is (str/starts-with? (nth lines 2) "│   "))))

  (testing "a last branch's grandchildren get spaces (no │)"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-C" "Gamma"
                                      :children [(node "kno-D" "Delta")])])
          lines (str/split-lines (output/dep-tree-text tree))]
      (is (str/starts-with? (nth lines 2) "    "))
      (is (not (str/starts-with? (nth lines 2) "│"))))))

(deftest dep-tree-text-seen-before-test
  (testing "seen-before? nodes get a trailing ↑ marker"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-D" "Delta" :seen-before? true)])
          lines (str/split-lines (output/dep-tree-text tree))]
      (is (str/includes? (last lines) "↑"))
      (is (str/includes? (last lines) "Delta")))))

(deftest dep-tree-text-missing-test
  (testing "missing? nodes render with [missing] marker (no title)"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-ghost" nil :missing? true)])
          lines (str/split-lines (output/dep-tree-text tree))]
      (is (str/includes? (last lines) "kno-ghost"))
      (is (str/includes? (last lines) "[missing]"))))

  (testing "missing? root renders as a single line"
    (let [tree (node "kno-ghost" nil :missing? true)
          out  (output/dep-tree-text tree)]
      (is (str/includes? out "kno-ghost"))
      (is (str/includes? out "[missing]")))))

(deftest dep-tree-json-test
  (testing "root with no children: bare JSON object with id, title, status, deps:[]"
    (let [tree (node "kno-A" "Alpha")
          out  (output/dep-tree-json tree)]
      (is (str/starts-with? out "{"))
      (is (str/includes? out "\"id\":\"kno-A\""))
      (is (str/includes? out "\"title\":\"Alpha\""))
      (is (str/includes? out "\"status\":\"open\""))
      (is (str/includes? out "\"deps\":[]"))))

  (testing "nested children appear under :deps as a JSON array"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-B" "Beta")])
          out  (output/dep-tree-json tree)]
      (is (str/includes? out "\"deps\":[{"))
      (is (str/includes? out "\"id\":\"kno-B\""))
      (is (str/includes? out "\"title\":\"Beta\""))))

  (testing "missing nodes serialize with \"missing\":true and no title/status"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-ghost" nil :missing? true)])
          out  (output/dep-tree-json tree)]
      (is (str/includes? out "\"id\":\"kno-ghost\""))
      (is (str/includes? out "\"missing\":true"))))

  (testing "seen-before? nodes serialize with \"seen_before\":true"
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-D" "Delta" :seen-before? true)])
          out  (output/dep-tree-json tree)]
      (is (str/includes? out "\"seen_before\":true"))))

  (testing "exact JSON snapshot — pin the public contract"
    ;; Locks the documented shape (issue-0005 lines 127-135): bare nested
    ;; object, snake_case `seen_before`, missing-as-leaf with no `deps`,
    ;; seen-before-as-leaf with no `deps`, normal leaves carry `deps:[]`.
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-G" nil :missing? true)
                                (node "kno-D" "Delta" :seen-before? true)
                                (node "kno-B" "Beta")])]
      (is (= (str "{\"id\":\"kno-A\",\"title\":\"Alpha\",\"status\":\"open\","
                  "\"deps\":["
                  "{\"id\":\"kno-G\",\"missing\":true},"
                  "{\"id\":\"kno-D\",\"title\":\"Delta\",\"status\":\"open\","
                  "\"seen_before\":true},"
                  "{\"id\":\"kno-B\",\"title\":\"Beta\",\"status\":\"open\","
                  "\"deps\":[]}"
                  "]}")
             (output/dep-tree-json tree))))))
