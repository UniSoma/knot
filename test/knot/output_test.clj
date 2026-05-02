(ns knot.output-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.output :as output]))

(deftest show-text-test
  (testing "renders frontmatter and body for the show command"
    (let [ticket {:frontmatter {:id "kno-01abc"
                                :title "Fix login"
                                :status "open"
                                :type "task"
                                :priority 2}
                  :body "Description text.\n"}
          s (output/show-text ticket)]
      (is (str/includes? s "id: kno-01abc"))
      (is (str/includes? s "title: Fix login"))
      (is (str/includes? s "status: open"))
      (is (str/includes? s "type: task"))
      (is (str/includes? s "Description text.")))))

(defn- mk-resolved
  "Test helper: build a `{:id ... :ticket ...}` resolved-ref entry whose
   embedded ticket carries the title in frontmatter."
  [id title]
  {:id id :ticket {:frontmatter {:id id :title title :status "open"}
                   :body ""}})

(defn- mk-missing
  [id]
  {:id id :missing? true})

(deftest show-text-inverses-test
  (testing "all four sections appear in order when populated"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                  :body "Body text.\n"}
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
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                  :body "Body text.\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)
          body-i (str/index-of s "Body text.")
          sec-i  (str/index-of s "## Blockers")]
      (is (< body-i sec-i)
          "computed sections come after the rendered body")))

  (testing "empty sections are omitted entirely (no header line)"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (str/includes? s "## Blockers"))
      (is (not (str/includes? s "## Blocking")))
      (is (not (str/includes? s "## Children")))
      (is (not (str/includes? s "## Linked")))))

  (testing "all four sections empty: output has no computed-section headers"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [] :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (not (str/includes? s "## Blockers")))
      (is (not (str/includes? s "## Blocking")))
      (is (not (str/includes? s "## Children")))
      (is (not (str/includes? s "## Linked")))))

  (testing "each entry renders as `- <id>  <title>` (resolved)"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")
                               (mk-resolved "kno-C" "Gamma")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (str/includes? s "- kno-B  Beta"))
      (is (str/includes? s "- kno-C  Gamma"))))

  (testing "missing refs render with `[missing]` marker"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [(mk-missing "kno-ghost")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)]
      (is (str/includes? s "- kno-ghost  [missing]"))))

  (testing "single-arity show-text (no inverses) still works"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          s (output/show-text ticket)]
      (is (str/includes? s "title: Alpha"))
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
                  :title "Fix login bug"
                  :status "open"
                  :priority 2
                  :mode "afk"
                  :type "task"
                  :assignee "alice"}
    :body ""}
   {:frontmatter {:id "kno-01abcd0002"
                  :title "Critical pager outage during peak hours please address"
                  :status "in_progress"
                  :priority 0
                  :mode "hitl"
                  :type "bug"
                  :assignee "bob"}
    :body ""}])

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
    (let [tickets [{:frontmatter {:id "x" :title "T" :status "open" :priority 0
                                  :mode "hitl" :type "task" :assignee "a"}
                    :body ""}
                   {:frontmatter {:id "y" :title "T" :status "open" :priority 4
                                  :mode "hitl" :type "task" :assignee "a"}
                    :body ""}]
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

(deftest status-role-test
  (testing "status in :terminal-statuses → :terminal"
    (is (= :terminal
           (output/status-role "closed"
                               ["open" "in_progress" "closed"]
                               #{"closed"}
                               "in_progress"))))
  (testing "status equal to :active-status → :active"
    (is (= :active
           (output/status-role "in_progress"
                               ["open" "in_progress" "closed"]
                               #{"closed"}
                               "in_progress"))))
  (testing "first non-active non-terminal status in :statuses ordering → :open"
    (is (= :open
           (output/status-role "open"
                               ["open" "in_progress" "closed"]
                               #{"closed"}
                               "in_progress"))))
  (testing "custom :statuses with extra non-special entry → :other"
    (let [statuses ["open" "active" "review" "closed"]
          terminal #{"closed"}
          active   "active"]
      (is (= :terminal (output/status-role "closed" statuses terminal active)))
      (is (= :active   (output/status-role "active" statuses terminal active)))
      (is (= :open     (output/status-role "open"   statuses terminal active)))
      (is (= :other    (output/status-role "review" statuses terminal active)))))
  (testing "the :open lane is the first non-active non-terminal in :statuses order"
    (let [statuses ["todo" "open" "active" "closed"]
          terminal #{"closed"}
          active   "active"]
      (is (= :open  (output/status-role "todo" statuses terminal active)))
      (is (= :other (output/status-role "open" statuses terminal active)))))
  (testing "status not present in :statuses at all → :other"
    (is (= :other
           (output/status-role "wat"
                               ["open" "in_progress" "closed"]
                               #{"closed"}
                               "in_progress")))))

(def ^:private custom-statuses-tickets
  [{:frontmatter {:id "kno-01abcd0010"
                  :title "Custom open"
                  :status "open"
                  :priority 2
                  :mode "hitl"
                  :type "task"
                  :assignee "alice"}
    :body ""}
   {:frontmatter {:id "kno-01abcd0011"
                  :title "Custom active"
                  :status "active"
                  :priority 2
                  :mode "hitl"
                  :type "task"
                  :assignee "alice"}
    :body ""}
   {:frontmatter {:id "kno-01abcd0012"
                  :title "Custom review"
                  :status "review"
                  :priority 2
                  :mode "hitl"
                  :type "task"
                  :assignee "alice"}
    :body ""}
   {:frontmatter {:id "kno-01abcd0013"
                  :title "Custom closed"
                  :status "closed"
                  :priority 2
                  :mode "hitl"
                  :type "task"
                  :assignee "alice"}
    :body ""}])

(deftest ls-table-default-statuses-color-roles-test
  (testing "default :statuses preserves current colors (closed=dim, in_progress=yellow, open=cyan)"
    (let [out (output/ls-table sample-ls-tickets
                               {:color? true :tty? true :width 200
                                :statuses          ["open" "in_progress" "closed"]
                                :terminal-statuses #{"closed"}
                                :active-status     "in_progress"})]
      (is (re-find #"\[36mopen" out)
          "open lane wraps the cell in the :cyan SGR (36)")
      (is (re-find #"\[33min_progress" out)
          "active lane (in_progress) wraps the cell in the :yellow SGR (33)"))))

(deftest ls-table-custom-statuses-color-roles-test
  (testing "with :active-status \"active\" and a custom :statuses, the active lane is yellow"
    (let [out (output/ls-table custom-statuses-tickets
                               {:color? true :tty? true :width 200
                                :statuses          ["open" "active" "review" "closed"]
                                :terminal-statuses #{"closed"}
                                :active-status     "active"})]
      (is (re-find #"\[36mopen" out)
          "first non-active non-terminal lane (\"open\") wraps in :cyan SGR (36)")
      (is (re-find #"\[33mactive" out)
          "active-status lane (\"active\") wraps in :yellow SGR (33)")
      (is (re-find #"\[2mclosed" out)
          "terminal lane (\"closed\") wraps in :dim SGR (2)")
      (is (not (re-find #"\[[0-9;]+mreview" out))
          "non-special status (\"review\") receives no SGR around the status cell"))))

(deftest ls-table-no-status-options-back-compat-test
  (testing "ls-table without status options still colors in_progress yellow (defaults)"
    (let [out (output/ls-table sample-ls-tickets {:color? true :tty? true :width 200})]
      (is (re-find #"\[33min_progress" out)
          "defaults preserve the in_progress=yellow color"))))

(deftest show-json-test
  (testing "renders a v0.3 success envelope wrapping the ticket"
    (let [ticket {:frontmatter {:id "kno-01abc"
                                :title "Title"
                                :status "open"
                                :type "task"
                                :priority 2
                                :external_refs ["JIRA-1"]}
                  :body "Body.\n"}
          out (output/show-json ticket)
          parsed (json/parse-string out true)]
      (is (str/starts-with? out "{"))
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (map? (:data parsed)))))
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
    (let [ticket {:frontmatter {:id "kno-01abc"} :body "Body content"}
          out (output/show-json ticket)]
      (is (str/includes? out "\"body\""))
      (is (str/includes? out "Body content")))))

(deftest show-json-inverses-test
  (testing "show-json with inverses adds blockers/blocking/children/linked arrays"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
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
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"id\":\"kno-B\""))
      (is (str/includes? out "\"title\":\"Beta\""))
      (is (str/includes? out "\"status\":\"open\""))))

  (testing "missing entries serialize with `missing:true` and no title/status"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [(mk-missing "kno-ghost")]
                    :blocking [] :children [] :linked []}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"id\":\"kno-ghost\""))
      (is (str/includes? out "\"missing\":true"))))

  (testing "empty sections serialize as []"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [] :blocking [] :children [] :linked []}
          out (output/show-json ticket inverses)]
      (is (str/includes? out "\"blockers\":[]"))
      (is (str/includes? out "\"blocking\":[]"))
      (is (str/includes? out "\"children\":[]"))
      (is (str/includes? out "\"linked\":[]"))))

  (testing "single-arity show-json (no inverses) does not add inverse fields"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          out (output/show-json ticket)]
      (is (not (str/includes? out "\"blockers\"")))
      (is (not (str/includes? out "\"blocking\"")))
      (is (not (str/includes? out "\"children\"")))
      (is (not (str/includes? out "\"linked\""))))))

(deftest ls-json-test
  (testing "renders a v0.3 success envelope wrapping the ticket array"
    (let [tickets [{:frontmatter {:id "a" :status "open"} :body ""}
                   {:frontmatter {:id "b" :status "in_progress"} :body ""}]
          out (output/ls-json tickets)
          parsed (json/parse-string out true)]
      (is (str/starts-with? (str/trim out) "{"))
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (vector? (:data parsed)))))
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
  (testing "an empty list renders as :data []"
    (let [parsed (json/parse-string (output/ls-json []) true)]
      (is (= [] (:data parsed))))))

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
    (not missing?) (assoc :ticket {:frontmatter {:id id
                                                 :title title
                                                 :status "open"}
                                   :body ""})
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
  (testing "root with no children: envelope-wrapped object with id, title, status, deps:[]"
    (let [tree (node "kno-A" "Alpha")
          out  (output/dep-tree-json tree)
          parsed (json/parse-string out true)]
      (is (str/starts-with? out "{"))
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= "kno-A" (get-in parsed [:data :id])))
      (is (= "Alpha" (get-in parsed [:data :title])))
      (is (= "open" (get-in parsed [:data :status])))
      (is (= [] (get-in parsed [:data :deps])))))

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
    ;; Locks the documented shape: v0.3 envelope wrapping a nested
    ;; object, snake_case `seen_before`, missing-as-leaf with no `deps`,
    ;; seen-before-as-leaf with no `deps`, normal leaves carry `deps:[]`.
    (let [tree (node "kno-A" "Alpha"
                     :children [(node "kno-G" nil :missing? true)
                                (node "kno-D" "Delta" :seen-before? true)
                                (node "kno-B" "Beta")])]
      (is (= (str "{\"schema_version\":1,\"ok\":true,\"data\":"
                  "{\"id\":\"kno-A\",\"title\":\"Alpha\",\"status\":\"open\","
                  "\"deps\":["
                  "{\"id\":\"kno-G\",\"missing\":true},"
                  "{\"id\":\"kno-D\",\"title\":\"Delta\",\"status\":\"open\","
                  "\"seen_before\":true},"
                  "{\"id\":\"kno-B\",\"title\":\"Beta\",\"status\":\"open\","
                  "\"deps\":[]}"
                  "]}}")
             (output/dep-tree-json tree))))))

(defn- mk-prime-ticket
  "Build a ticket map suitable for the prime renderers. `opts` overrides
   any frontmatter field; `:title` populates the frontmatter `:title`."
  [{:keys [id status priority mode title updated created]}]
  {:frontmatter (cond-> {:id id :title (or title "Untitled")}
                  status   (assoc :status status)
                  priority (assoc :priority priority)
                  mode     (assoc :mode mode)
                  updated  (assoc :updated updated)
                  created  (assoc :created created))
   :body ""})

(def ^:private sample-prime-data
  {:project       {:found? true
                   :prefix "kno"
                   :name "knot"
                   :live-count 4
                   :archive-count 2}
   :in-progress   [(mk-prime-ticket {:id "kno-ip01" :status "in_progress"
                                     :mode "afk" :priority 1
                                     :title "Working on alpha"
                                     :updated "2026-04-28T10:00:00Z"})
                   (mk-prime-ticket {:id "kno-ip02" :status "in_progress"
                                     :mode "hitl" :priority 2
                                     :title "Working on beta"
                                     :updated "2026-04-27T10:00:00Z"})]
   :ready         [(mk-prime-ticket {:id "kno-rd01" :status "open"
                                     :mode "afk" :priority 0
                                     :title "Ready highest"
                                     :created "2026-04-28T09:00:00Z"})
                   (mk-prime-ticket {:id "kno-rd02" :status "open"
                                     :mode "hitl" :priority 2
                                     :title "Ready lower"
                                     :created "2026-04-27T09:00:00Z"})]
   :ready-truncated? false
   :ready-remaining 0})

(deftest prime-text-canonical-sections-test
  (testing "preamble paragraph appears at the top before all section headings"
    (let [out (output/prime-text sample-prime-data)
          first-section (or (str/index-of out "## ") (count out))
          preamble (subs out 0 first-section)]
      (is (pos? (count (str/trim preamble)))
          "preamble is a non-empty paragraph above the first heading")))

  (testing "project, in-progress, ready, commands sections appear in canonical order"
    (let [out (output/prime-text sample-prime-data)
          p-i  (str/index-of out "## Project")
          ip-i (str/index-of out "## In Progress")
          rd-i (str/index-of out "## Ready")
          cm-i (str/index-of out "## Commands")]
      (is (every? some? [p-i ip-i rd-i cm-i])
          "all four section headings present (preamble has no heading)")
      (is (< p-i ip-i rd-i cm-i)
          "section headings appear in the canonical order"))))

(deftest prime-text-project-metadata-test
  (testing "project section renders prefix"
    (let [out (output/prime-text sample-prime-data)]
      (is (str/includes? out "kno"))))

  (testing "project section renders project name when config provides one"
    (let [out (output/prime-text sample-prime-data)]
      (is (str/includes? out "knot"))))

  (testing "project section omits the project name when not provided"
    (let [data (assoc-in sample-prime-data [:project :name] nil)
          out  (output/prime-text data)
          ;; Capture only the Project section so a stray "knot" elsewhere
          ;; (preamble, schema cheatsheet) does not produce a false positive.
          start (str/index-of out "## Project")
          end   (or (str/index-of out "## In Progress") (count out))
          section (subs out start end)]
      (is (not (re-find #"\bname[^a-z]" section))
          "no name field rendered when :name is absent")))

  (testing "project section renders live and archive counts"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## Project")
          end   (str/index-of out "## In Progress")
          section (subs out start end)]
      (is (re-find #"\b4\b" section) "live count appears")
      (is (re-find #"\b2\b" section) "archive count appears"))))

(deftest prime-text-ticket-line-format-test
  (testing "in-progress ticket lines contain id, mode, priority, and title"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## In Progress")
          end   (str/index-of out "## Ready")
          section (subs out start end)]
      (is (str/includes? section "kno-ip01"))
      (is (str/includes? section "afk"))
      (is (str/includes? section "1"))
      (is (str/includes? section "Working on alpha"))))

  (testing "ready ticket lines contain id, mode, priority, and title"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## Ready")
          end   (or (str/index-of out "## Commands") (count out))
          section (subs out start end)]
      (is (str/includes? section "kno-rd01"))
      (is (str/includes? section "afk"))
      (is (str/includes? section "0"))
      (is (str/includes? section "Ready highest"))))

  (testing "the four fields appear in canonical order on each ticket line"
    (let [data (assoc sample-prime-data
                      :ready
                      [(mk-prime-ticket {:id "kno-letters"
                                         :status "open"
                                         :mode "hitl"
                                         :priority 2
                                         :title "Order check title"
                                         :created "2026-04-27T09:00:00Z"})])
          out (output/prime-text data)
          start (str/index-of out "## Ready")
          end   (or (str/index-of out "## Commands") (count out))
          section (subs out start end)
          ;; The id contains no digits and the title contains no digits,
          ;; so the digit `2` unambiguously locates the priority column.
          line (some (fn [l] (when (str/includes? l "kno-letters") l))
                     (str/split-lines section))]
      (is (some? line) "the kno-letters line is present")
      (let [id-i  (str/index-of line "kno-letters")
            mode-i (str/index-of line "hitl")
            pri-i (str/index-of line "2")
            title-i (str/index-of line "Order check title")]
        (is (every? some? [id-i mode-i pri-i title-i]))
        (is (< id-i mode-i pri-i title-i)
            "id, mode, priority, title appear left-to-right on the line")))))

(deftest prime-text-ready-order-preserved-test
  (testing "the ready section emits tickets in the order supplied (caller controls the sort)"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## Ready")
          end   (or (str/index-of out "## Commands") (count out))
          section (subs out start end)
          rd01-i (str/index-of section "kno-rd01")
          rd02-i (str/index-of section "kno-rd02")]
      (is (and rd01-i rd02-i))
      (is (< rd01-i rd02-i)
          "renderer emits tickets in input order — caller does the sorting"))))

(deftest prime-text-truncation-footer-test
  (testing "truncation footer appears when :ready-truncated? is true"
    (let [data (assoc sample-prime-data
                      :ready-truncated? true
                      :ready-remaining 5)
          out (output/prime-text data)]
      (is (str/includes? out "+5 more"))
      (is (str/includes? out "knot ready"))))

  (testing "no truncation footer when :ready-truncated? is false"
    (let [out (output/prime-text sample-prime-data)]
      (is (not (str/includes? out "more (run"))
          "no truncation footer when ready section is not truncated"))))

(deftest prime-text-empty-sections-test
  (testing "zero-ticket project: Project + Ready emit headings; empty In Progress is suppressed"
    (let [data {:project {:found? true
                          :prefix "kno"
                          :name nil
                          :live-count 0
                          :archive-count 0}
                :in-progress []
                :ready []
                :ready-truncated? false
                :ready-remaining 0}
          out (output/prime-text data)]
      (is (str/includes? out "## Project"))
      (is (str/includes? out "## Ready"))
      (is (str/includes? out "## Commands"))
      (is (not (str/includes? out "## In Progress"))
          "empty In Progress is dropped — heading-only sections are dead weight")
      (is (not (str/includes? out "## Schema"))
          "Schema cheatsheet was hardcoded and has been retired"))))

(deftest prime-text-no-project-test
  (testing "preamble directs the user to `knot init` when no project is found"
    (let [data {:project {:found? false}
                :in-progress []
                :ready []
                :ready-truncated? false
                :ready-remaining 0}
          out (output/prime-text data)]
      (is (str/includes? out "knot init")
          "preamble references `knot init` so the agent knows how to bootstrap"))))

(deftest prime-text-directive-opening-test
  (testing "first non-blank line is a directive about using `knot`, not a description"
    (let [out (output/prime-text sample-prime-data)
          first-line (->> (str/split-lines out)
                          (remove str/blank?)
                          first)]
      (is (some? first-line) "output has a non-blank line")
      (is (not (re-find #"^You are working in" first-line))
          "first line is not the legacy descriptive preamble")
      (is (re-find #"(?i)^use\b.*\bknot\b" first-line)
          "first line is a directive that names the knot CLI"))))

(deftest prime-text-user-says-mapping-test
  (testing "output contains a user-phrase mapping covering the canonical intents"
    (let [out (output/prime-text sample-prime-data)]
      (is (str/includes? out "what's next")
          "covers 'what's next?'")
      (is (str/includes? out "tackle")
          "covers 'let's tackle <id>'")
      (is (or (str/includes? out "I'm done")
              (str/includes? out "let's close"))
          "covers 'I'm done / shipped / let's close'")
      (is (or (str/includes? out "note that")
              (str/includes? out "FYI"))
          "covers 'note that / FYI'")
      (is (str/includes? out "blocked on")
          "covers 'blocked on'"))))

(deftest prime-text-negative-space-test
  (testing "output explicitly tells the agent NOT to cat or hand-edit ticket files"
    (let [out (output/prime-text sample-prime-data)]
      (is (re-find #"(?i)don't|do not" out)
          "output carries an explicit negative directive")
      (is (str/includes? out "cat")
          "output specifically calls out `cat`")
      (is (str/includes? out ".tickets")
          "output references the .tickets/ directory the agent might otherwise touch"))))

(deftest prime-text-afk-mode-preamble-test
  (testing "with :mode \"afk\", the preamble shifts to the autonomous-agent flow"
    (let [data (assoc sample-prime-data :mode "afk")
          out  (output/prime-text data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"(?i)autonomous|agent" preamble)
          "afk preamble is framed for autonomous agents")
      (is (re-find #"knot ready --mode afk" preamble)
          "afk preamble surfaces the candidate-enumeration command")
      (is (re-find #"knot start" preamble)
          "afk preamble surfaces the claim command")
      (is (re-find #"knot close" preamble)
          "afk preamble surfaces the ship command")
      (is (not (re-find #"what's next" preamble))
          "afk preamble drops the human-oriented intent phrases")
      (is (re-find #"(?i)hitl" preamble)
          "afk preamble warns against picking up hitl tickets")))

  (testing "without :mode, the preamble keeps the human-oriented intent table"
    (let [out (output/prime-text sample-prime-data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"what's next" preamble)
          "default preamble retains the human intent phrases")))

  (testing "with :mode \"hitl\", the human-oriented preamble is preserved"
    (let [data (assoc sample-prime-data :mode "hitl")
          out  (output/prime-text data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"what's next" preamble)
          "hitl mode is the human default — no shift to agent flow")))

  (testing "mode dispatch tolerates keyword, uppercase, and whitespace"
    (doseq [m [:afk "AFK" "  afk  " "Afk"]]
      (let [data (assoc sample-prime-data :mode m)
            out  (output/prime-text data)
            first-section (str/index-of out "## ")
            preamble (subs out 0 first-section)]
        (is (re-find #"(?i)autonomous" preamble)
            (str "mode " (pr-str m) " should still pick the afk preamble"))))))

(deftest prime-text-mentions-skill-test
  (testing "preamble mentions the `knot` skill so non-CC agents discover the canonical doc"
    (let [out (output/prime-text sample-prime-data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"(?i)skill" preamble)
          "preamble references the skill")
      (is (re-find #"(?i)knot.*skill|skill.*knot" preamble)
          "the skill mention is anchored to `knot`, not just any skill"))))

(deftest prime-text-stale-in-progress-flag-test
  (testing "in-progress entries carrying :prime-stale? render with a [stale] prefix"
    (let [stale-ticket  (-> (mk-prime-ticket {:id "kno-stale" :status "in_progress"
                                              :mode "afk" :priority 1
                                              :title "Stalled work"
                                              :updated "2026-04-01T10:00:00Z"})
                            (assoc :prime-stale? true))
          fresh-ticket  (mk-prime-ticket {:id "kno-fresh" :status "in_progress"
                                          :mode "hitl" :priority 2
                                          :title "Active work"
                                          :updated "2026-04-29T10:00:00Z"})
          data (assoc sample-prime-data :in-progress [stale-ticket fresh-ticket])
          out  (output/prime-text data)
          ip-start (str/index-of out "## In Progress")
          ip-end   (str/index-of out "## Ready")
          section  (subs out ip-start ip-end)]
      (is (re-find #"\[stale\]\s+kno-stale" section)
          "stalled ticket line is prefixed with [stale]")
      (is (not (re-find #"\[stale\]\s+kno-fresh" section))
          "fresh ticket line is not prefixed")))

  (testing "the [stale] prefix is omitted when :prime-stale? is absent or false"
    (let [t1 (mk-prime-ticket {:id "kno-a" :status "in_progress"
                               :mode "afk" :priority 1 :title "Alpha"
                               :updated "2026-04-29T10:00:00Z"})
          t2 (-> t1
                 (assoc :prime-stale? false)
                 (assoc-in [:frontmatter :id] "kno-b")
                 (assoc-in [:frontmatter :title] "Beta"))
          data (assoc sample-prime-data :in-progress [t1 t2])
          out  (output/prime-text data)
          ip-start (str/index-of out "## In Progress")
          ip-end   (str/index-of out "## Ready")
          section  (subs out ip-start ip-end)]
      (is (not (str/includes? section "[stale]"))
          "no prefix when the flag is absent or false"))))

(deftest prime-json-stale-flag-test
  (testing "in_progress entries carrying :prime-stale? get a \"stale\":true field"
    (let [stale-ticket (-> (mk-prime-ticket {:id "kno-stale" :status "in_progress"
                                             :mode "afk" :priority 1
                                             :title "Stalled work"
                                             :updated "2026-04-01T10:00:00Z"})
                           (assoc :prime-stale? true))
          fresh-ticket (mk-prime-ticket {:id "kno-fresh" :status "in_progress"
                                         :mode "hitl" :priority 2
                                         :title "Active"
                                         :updated "2026-04-29T10:00:00Z"})
          data (assoc sample-prime-data :in-progress [stale-ticket fresh-ticket])
          out  (output/prime-json data)]
      (is (str/includes? out "\"id\":\"kno-stale\""))
      (is (re-find #"\"id\":\"kno-stale\"[^}]*\"stale\":true" out)
          "stale ticket carries \"stale\":true")
      (is (not (re-find #"\"id\":\"kno-fresh\"[^}]*\"stale\":" out))
          "fresh ticket has no \"stale\" key (omitted to keep payload tight)"))))

(deftest prime-text-recently-closed-section-test
  (testing "## Recently Closed heading appears between Ready and Commands when entries exist"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-cl01"
                        :title "Shipped feature X"
                        :closed "2026-04-29T10:00:00Z"
                        :summary "shipped in #482"}])
          out (output/prime-text data)
          rd-i (str/index-of out "## Ready")
          rc-i (str/index-of out "## Recently Closed")
          cm-i (str/index-of out "## Commands")]
      (is (every? some? [rd-i rc-i cm-i]))
      (is (< rd-i rc-i cm-i)
          "Recently Closed appears between Ready and Commands")))

  (testing "each entry surfaces id and title"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-aaa" :title "Alpha shipped" :closed "2026-04-29T10:00:00Z"}
                       {:id "kno-bbb" :title "Beta shipped"  :closed "2026-04-28T10:00:00Z"}])
          out (output/prime-text data)
          rc-start (str/index-of out "## Recently Closed")
          cm-start (str/index-of out "## Commands")
          section  (subs out rc-start cm-start)]
      (is (str/includes? section "kno-aaa"))
      (is (str/includes? section "Alpha shipped"))
      (is (str/includes? section "kno-bbb"))
      (is (str/includes? section "Beta shipped"))))

  (testing "entries with a :summary surface the summary text in the section"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-aaa" :title "Alpha" :closed "2026-04-29T10:00:00Z"
                        :summary "shipped in PR #482"}])
          out (output/prime-text data)
          rc-start (str/index-of out "## Recently Closed")
          cm-start (str/index-of out "## Commands")
          section  (subs out rc-start cm-start)]
      (is (str/includes? section "shipped in PR #482"))))

  (testing "the section is omitted entirely when :recently-closed is empty"
    (let [data (assoc sample-prime-data :recently-closed [])
          out (output/prime-text data)]
      (is (not (str/includes? out "## Recently Closed"))
          "no closes → no heading")))

  (testing "the section is omitted when :recently-closed is absent (backwards compat)"
    (let [out (output/prime-text sample-prime-data)]
      (is (not (str/includes? out "## Recently Closed"))
          "callers that don't supply :recently-closed get the prior behavior"))))

(deftest prime-text-commands-cheatsheet-trim-test
  (testing "Commands cheatsheet covers the 7 most-used lifecycle/read commands"
    (let [out      (output/prime-text sample-prime-data)
          start    (str/index-of out "## Commands")
          section  (subs out start (count out))]
      (doseq [cmd ["knot list" "knot ready" "knot show" "knot create"
                   "knot start" "knot close" "knot add-note"]]
        (is (str/includes? section cmd)
            (str cmd " stays in the prime cheatsheet — it's a high-frequency op")))))

  (testing "graph commands are NOT in the prime cheatsheet — they live in the skill"
    (let [out      (output/prime-text sample-prime-data)
          start    (str/index-of out "## Commands")
          section  (subs out start (count out))]
      (is (not (re-find #"(?m)^knot dep\b" section))
          "`knot dep` belongs in the skill, not the per-session primer")
      (is (not (re-find #"(?m)^knot link\b" section))
          "`knot link` belongs in the skill, not the per-session primer"))))

(deftest prime-text-active-status-cheatsheet-test
  (testing "Commands cheatsheet `knot start` line reflects :active-status from data"
    (let [data (assoc sample-prime-data :active-status "active")
          out  (output/prime-text data)
          start (str/index-of out "## Commands")
          section (subs out start (count out))
          start-line (some (fn [l] (when (re-find #"^knot start\b" l) l))
                           (str/split-lines section))]
      (is (some? start-line))
      (is (str/includes? start-line "transition to active")
          "knot start line reflects the configured active status")
      (is (not (str/includes? start-line "in_progress"))
          "literal in_progress is gone when active-status is something else")))

  (testing "no-project caller (prime-cmd) threads :active-status from (config/defaults) so the cheatsheet renders in_progress"
    ;; The renderer requires :active-status in the data arg — the
    ;; no-project branch in prime-cmd substitutes (config/defaults) so
    ;; the cheatsheet still renders. This test mirrors what that caller
    ;; does, locking in the contract that the caller owns the fallback.
    (let [data {:project {:found? false}
                :in-progress []
                :ready []
                :ready-truncated? false
                :ready-remaining 0
                :active-status "in_progress"}
          out (output/prime-text data)
          start (str/index-of out "## Commands")
          section (subs out start (count out))
          start-line (some (fn [l] (when (re-find #"^knot start\b" l) l))
                           (str/split-lines section))]
      (is (some? start-line))
      (is (str/includes? start-line "transition to in_progress")
          "default :active-status from (config/defaults) yields the in_progress literal"))))

(deftest prime-text-close-shows-summary-flag-test
  (testing "Commands cheatsheet documents --summary on the close line, not buried in the user-says mapping"
    (let [out      (output/prime-text sample-prime-data)
          start    (str/index-of out "## Commands")
          end      (count out)
          section  (subs out start end)
          close-line (some (fn [l] (when (re-find #"^knot close\b" l) l))
                           (str/split-lines section))]
      (is (some? close-line)
          "Commands section has a dedicated `knot close` line (not lumped with start)")
      (is (str/includes? close-line "--summary")
          "close line surfaces the --summary flag inline so the agent doesn't cross-reference"))))

(deftest prime-text-section-nudges-test
  (testing "in-progress section carries a one-line behavioral nudge under its heading"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## In Progress")
          end   (str/index-of out "## Ready")
          section (subs out start end)]
      (is (or (str/includes? section "Resume")
              (str/includes? section "mid-stream"))
          "In Progress section nudges the agent to resume mid-stream work")))

  (testing "ready section carries a one-line behavioral nudge under its heading"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## Ready")
          ;; Stop at the next `## ` heading after Ready (Commands or Schema)
          ;; so the nudge match is scoped to the Ready section body.
          next-sec (or (str/index-of out "## Commands" start)
                       (str/index-of out "## Schema" start)
                       (count out))
          section  (subs out start next-sec)]
      (is (or (str/includes? section "what's next")
              (str/includes? section "recommend")
              (str/includes? section "confirm"))
          "Ready section nudges the agent about recommending the top entry"))))

(deftest prime-json-shape-test
  (testing "renders a v0.3 success envelope wrapping the prime payload"
    (let [out (output/prime-json sample-prime-data)
          parsed (json/parse-string out true)]
      (is (str/starts-with? out "{"))
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (map? (:data parsed)))))

  (testing "uses snake_case top-level keys: project, in_progress, ready, ready_truncated, ready_remaining"
    (let [out (output/prime-json sample-prime-data)]
      (is (str/includes? out "\"project\""))
      (is (str/includes? out "\"in_progress\""))
      (is (str/includes? out "\"ready\""))
      (is (str/includes? out "\"ready_truncated\""))
      (is (str/includes? out "\"ready_remaining\""))))

  (testing "drops the preamble, schema, and cheatsheet (not present in JSON)"
    (let [out (output/prime-json sample-prime-data)]
      (is (not (str/includes? out "## Schema")))
      (is (not (str/includes? out "## In Progress")))))

  (testing "in_progress and ready arrays carry the ticket ids"
    (let [out (output/prime-json sample-prime-data)]
      (is (str/includes? out "\"kno-ip01\""))
      (is (str/includes? out "\"kno-rd01\""))
      (is (str/includes? out "\"kno-rd02\""))))

  (testing "ready_truncated reflects the input flag"
    (let [out  (output/prime-json sample-prime-data)
          out2 (output/prime-json (assoc sample-prime-data
                                         :ready-truncated? true
                                         :ready-remaining 5))]
      (is (str/includes? out  "\"ready_truncated\":false"))
      (is (str/includes? out2 "\"ready_truncated\":true"))
      (is (str/includes? out2 "\"ready_remaining\":5"))))

  (testing "project carries prefix and counts; name omitted when absent"
    (let [out (output/prime-json sample-prime-data)
          data (assoc-in sample-prime-data [:project :name] nil)
          out-no-name (output/prime-json data)]
      (is (str/includes? out "\"prefix\":\"kno\""))
      (is (str/includes? out "\"name\":\"knot\""))
      (is (str/includes? out "\"live_count\":4"))
      (is (str/includes? out "\"archive_count\":2"))
      (is (not (str/includes? out-no-name "\"name\""))
          "name field is omitted when no name is provided"))))

(deftest prime-json-no-project-test
  (testing "no-project case emits an envelope with empty arrays and counts of 0"
    (let [data {:project {:found? false}
                :in-progress []
                :ready []
                :ready-truncated? false
                :ready-remaining 0}
          parsed (json/parse-string (output/prime-json data) true)]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= [] (get-in parsed [:data :in_progress])))
      (is (= [] (get-in parsed [:data :ready])))
      (is (= false (get-in parsed [:data :ready_truncated])))
      (is (= 0 (get-in parsed [:data :ready_remaining]))))))

;; -----------------------------------------------------------------------
;; Title-from-frontmatter tests
;;
;; Every read site that historically called `extract-title` on the body
;; should now read `(:title (:frontmatter ticket))` and fall back to "" when
;; absent. These tickets supply a frontmatter title and either an empty
;; body or a body without an H1 — proving the read path no longer depends
;; on the body H1.
;; -----------------------------------------------------------------------

(defn- title-only-ticket
  "Build a ticket whose title lives in frontmatter only — body has no H1."
  [id title]
  {:frontmatter {:id id :title title :status "open"}
   :body ""})

(deftest show-text-inverse-line-reads-frontmatter-title-test
  (testing "inverse entries render the frontmatter `:title` (no H1 in body)"
    (let [root      {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                     :body ""}
          inverses  {:blockers [{:id "kno-B"
                                 :ticket (title-only-ticket "kno-B" "Beta from FM")}]
                     :blocking [] :children [] :linked []}
          out (output/show-text root inverses)]
      (is (str/includes? out "- kno-B  Beta from FM"))))

  (testing "missing :title in inverse entry frontmatter renders as empty (no crash)"
    (let [root     {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                    :body ""}
          inverses {:blockers [{:id "kno-B"
                                :ticket {:frontmatter {:id "kno-B" :status "open"}
                                         :body ""}}]
                    :blocking [] :children [] :linked []}
          out (output/show-text root inverses)]
      (is (str/includes? out "- kno-B  ")
          "row prefix is present"))))

(deftest show-json-inverse-reads-frontmatter-title-test
  (testing "show-json inverse entries surface the frontmatter title"
    (let [root     {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                    :body ""}
          inverses {:blockers [{:id "kno-B"
                                :ticket (title-only-ticket "kno-B" "Beta from FM")}]
                    :blocking [] :children [] :linked []}
          out (output/show-json root inverses)]
      (is (str/includes? out "\"title\":\"Beta from FM\""))))

  (testing "missing :title fallback yields empty string in JSON"
    (let [root     {:frontmatter {:id "kno-A" :title "A" :status "open"}
                    :body ""}
          inverses {:blockers [{:id "kno-B"
                                :ticket {:frontmatter {:id "kno-B" :status "open"}
                                         :body ""}}]
                    :blocking [] :children [] :linked []}
          out (output/show-json root inverses)]
      (is (str/includes? out "\"title\":\"\"")))))

(deftest ls-table-reads-frontmatter-title-test
  (testing "ls-table TITLE column comes from frontmatter, not body H1"
    (let [tickets [(assoc-in (title-only-ticket "kno-1" "Title from frontmatter")
                             [:frontmatter :priority] 2)]
          out (output/ls-table tickets {:color? false :tty? false})]
      (is (str/includes? out "Title from frontmatter"))))

  (testing "ticket missing :title in frontmatter still renders without crash"
    (let [tickets [{:frontmatter {:id "kno-2" :status "open" :priority 2
                                  :mode "afk" :type "task" :assignee "x"}
                    :body ""}]
          out (output/ls-table tickets {:color? false :tty? false})]
      (is (str/includes? out "kno-2")))))

(deftest ls-json-includes-frontmatter-title-test
  (testing "ls-json output includes the frontmatter title for each ticket"
    (let [tickets [(title-only-ticket "kno-1" "From frontmatter")]
          out (output/ls-json tickets)]
      (is (str/includes? out "\"title\":\"From frontmatter\"")))))

(deftest dep-tree-text-reads-frontmatter-title-test
  (testing "dep-tree node label comes from frontmatter, not body H1"
    (let [tree {:id "kno-A"
                :ticket (title-only-ticket "kno-A" "Root title FM")
                :children [{:id "kno-B"
                            :ticket (title-only-ticket "kno-B" "Child FM")}]}
          out (output/dep-tree-text tree)]
      (is (str/includes? out "Root title FM"))
      (is (str/includes? out "Child FM"))))

  (testing "seen-before? leaf reads title from frontmatter"
    (let [tree {:id "kno-A"
                :ticket (title-only-ticket "kno-A" "Root")
                :children [{:id "kno-D"
                            :ticket (title-only-ticket "kno-D" "Delta FM")
                            :seen-before? true}]}
          out (output/dep-tree-text tree)]
      (is (str/includes? out "Delta FM ↑")))))

(deftest dep-tree-json-reads-frontmatter-title-test
  (testing "dep-tree-json title field is sourced from frontmatter"
    (let [tree {:id "kno-A"
                :ticket (title-only-ticket "kno-A" "Alpha FM")
                :children [{:id "kno-B"
                            :ticket (title-only-ticket "kno-B" "Beta FM")}]}
          out (output/dep-tree-json tree)]
      (is (str/includes? out "\"title\":\"Alpha FM\""))
      (is (str/includes? out "\"title\":\"Beta FM\"")))))

(deftest prime-text-reads-frontmatter-title-test
  (testing "prime-text in-progress and ready ticket lines surface the frontmatter title"
    (let [data {:project {:found? true :prefix "kno"
                          :live-count 1 :archive-count 0}
                :in-progress [{:frontmatter {:id "kno-ip01"
                                             :title "In-progress FM"
                                             :status "in_progress"
                                             :mode "afk"
                                             :priority 1}
                               :body ""}]
                :ready       [{:frontmatter {:id "kno-rd01"
                                             :title "Ready FM"
                                             :status "open"
                                             :mode "afk"
                                             :priority 0}
                               :body ""}]
                :ready-truncated? false
                :ready-remaining 0}
          out (output/prime-text data)]
      (is (str/includes? out "In-progress FM"))
      (is (str/includes? out "Ready FM")))))

(deftest prime-json-reads-frontmatter-title-test
  (testing "prime-json ticket entries surface the frontmatter title"
    (let [data {:project {:found? true :prefix "kno"
                          :live-count 0 :archive-count 0}
                :in-progress []
                :ready [{:frontmatter {:id "kno-rd01"
                                       :title "Ready JSON FM"
                                       :status "open"}
                         :body ""}]
                :ready-truncated? false
                :ready-remaining 0}
          out (output/prime-json data)]
      (is (str/includes? out "\"title\":\"Ready JSON FM\"")))))

;; -----------------------------------------------------------------------
;; v0.3 JSON envelope
;;
;; Every read-mode `--json` output is wrapped in
;; `{schema_version: 1, ok: true, data: ...}` on success and
;; `{schema_version: 1, ok: false, error: {code, message, candidates?}}`
;; on failure. `warnings: []` is reserved for a later slice — we don't
;; populate it here, but new keys must be tolerated by consumers.
;; -----------------------------------------------------------------------

(deftest envelope-success-test
  (testing "envelope wraps any payload in {schema_version, ok:true, data}"
    (let [parsed (json/parse-string (output/envelope-str {:hello "world"}) true)]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= {:hello "world"} (:data parsed)))
      (is (not (contains? parsed :error))
          "success envelope must not carry an :error key")))
  (testing "data may be a vector"
    (let [parsed (json/parse-string (output/envelope-str [1 2 3]) true)]
      (is (= [1 2 3] (:data parsed)))))
  (testing "data may be a string (e.g. nil-tolerant payloads)"
    (let [parsed (json/parse-string (output/envelope-str nil) true)]
      (is (= true (:ok parsed)))
      (is (contains? parsed :data))
      (is (nil? (:data parsed)))))
  (testing "schema_version is currently 1"
    (let [parsed (json/parse-string (output/envelope-str {}) true)]
      (is (= 1 (:schema_version parsed)))))
  (testing "with :meta in opts, the envelope includes a top-level :meta slot after :data"
    (let [out    (output/envelope-str {:id "kno-01abc"}
                                      {:meta {:archived_to ".tickets/archive/kno-01abc.md"}})
          parsed (json/parse-string out true)]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= {:id "kno-01abc"} (:data parsed)))
      (is (= ".tickets/archive/kno-01abc.md" (get-in parsed [:meta :archived_to])))
      ;; key order: schema_version, ok, data, meta — :meta lands after :data
      (is (str/index-of out "\"data\"") "data key present")
      (is (< (str/index-of out "\"data\"") (str/index-of out "\"meta\""))
          "meta key serializes after data")))
  (testing "without :meta, the envelope omits the meta slot"
    (let [out    (output/envelope-str {:id "kno-01abc"})
          parsed (json/parse-string out true)]
      (is (not (contains? parsed :meta))
          "no :meta key when none provided")))
  (testing ":meta combines with :ok? false"
    (let [out    (output/envelope-str {:scanned 5}
                                      {:ok? false :meta {:hint "try again"}})
          parsed (json/parse-string out true)]
      (is (= false (:ok parsed)))
      (is (= "try again" (get-in parsed [:meta :hint]))))))

(deftest envelope-error-test
  (testing "error envelope carries {schema_version, ok:false, error:{code, message}}"
    (let [parsed (json/parse-string
                  (output/error-envelope-str {:code "not_found"
                                              :message "no ticket matching xyz"})
                  true)]
      (is (= 1 (:schema_version parsed)))
      (is (= false (:ok parsed)))
      (is (= "not_found" (get-in parsed [:error :code])))
      (is (= "no ticket matching xyz" (get-in parsed [:error :message])))
      (is (not (contains? parsed :data))
          "error envelope must not carry a :data key")))
  (testing "ambiguous_id error includes candidates array"
    (let [parsed (json/parse-string
                  (output/error-envelope-str
                   {:code "ambiguous_id"
                    :message "ambiguous id 01: kno-01a, kno-01b"
                    :candidates ["kno-01a" "kno-01b"]})
                  true)]
      (is (= "ambiguous_id" (get-in parsed [:error :code])))
      (is (= ["kno-01a" "kno-01b"] (get-in parsed [:error :candidates])))))
  (testing "candidates is omitted when not provided"
    (let [parsed (json/parse-string
                  (output/error-envelope-str {:code "not_found"
                                              :message "x"})
                  true)]
      (is (not (contains? (:error parsed) :candidates)))))
  (testing "extra keys on error pass through unchanged so richer shapes can land later"
    (let [parsed (json/parse-string
                  (output/error-envelope-str {:code    "not_found"
                                              :message "no ticket matching x"
                                              :hint    "try a longer prefix"
                                              :input   "x"})
                  true)]
      (is (= "try a longer prefix" (get-in parsed [:error :hint])))
      (is (= "x" (get-in parsed [:error :input]))))))

(defn- envelope-of
  "Parse a JSON string as the v0.3 envelope and return the parsed map."
  [s]
  (json/parse-string s true))

(deftest show-json-envelope-test
  (testing "show-json wraps the ticket in the v0.3 envelope"
    (let [ticket {:frontmatter {:id "kno-01abc" :status "open" :priority 2}
                  :body "Body."}
          parsed (envelope-of (output/show-json ticket))]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= "kno-01abc" (get-in parsed [:data :id])))
      (is (= "open"      (get-in parsed [:data :status])))
      (is (= 2           (get-in parsed [:data :priority])))
      (is (= "Body."     (get-in parsed [:data :body])))))
  (testing "show-json with inverses places them inside :data alongside frontmatter"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"} :body ""}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          parsed (envelope-of (output/show-json ticket inverses))]
      (is (= true (:ok parsed)))
      (is (= "kno-A" (get-in parsed [:data :id])))
      (is (= [{:id "kno-B" :title "Beta" :status "open"}]
             (get-in parsed [:data :blockers]))))))

(deftest touched-ticket-json-test
  (testing "touched-ticket-json wraps a single post-mutation ticket in the v0.3 envelope, body included"
    (let [ticket {:frontmatter {:id "kno-01abc" :status "in_progress" :priority 2}
                  :body         "Body."}
          parsed (envelope-of (output/touched-ticket-json ticket))]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= "kno-01abc"     (get-in parsed [:data :id])))
      (is (= "in_progress"   (get-in parsed [:data :status])))
      (is (= "Body."         (get-in parsed [:data :body])))
      (is (not (contains? parsed :meta))
          "no :meta when none is supplied")))
  (testing "touched-ticket-json with :meta places archive_to in the top-level :meta slot"
    (let [ticket {:frontmatter {:id "kno-01abc" :status "closed"} :body ""}
          parsed (envelope-of
                  (output/touched-ticket-json
                   ticket
                   {:meta {:archived_to ".tickets/archive/kno-01abc.md"}}))]
      (is (= "closed" (get-in parsed [:data :status])))
      (is (= ".tickets/archive/kno-01abc.md"
             (get-in parsed [:meta :archived_to]))))))

(deftest touched-tickets-json-test
  (testing "touched-tickets-json wraps an array of post-mutation tickets, body excluded"
    (let [tickets [{:frontmatter {:id "kno-A" :status "open"} :body "Body A."}
                   {:frontmatter {:id "kno-B" :status "open"} :body "Body B."}]
          parsed  (envelope-of (output/touched-tickets-json tickets))]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (vector? (:data parsed)))
      (is (= 2 (count (:data parsed))))
      (is (= "kno-A" (get-in parsed [:data 0 :id])))
      (is (= "kno-B" (get-in parsed [:data 1 :id])))
      (is (not (contains? (get-in parsed [:data 0]) :body))
          "body excluded for compactness, like ls --json")))
  (testing "empty array still emits an envelope with :data []"
    (let [parsed (envelope-of (output/touched-tickets-json []))]
      (is (= true (:ok parsed)))
      (is (= [] (:data parsed))))))

(deftest ls-json-envelope-test
  (testing "ls-json wraps the ticket array in the v0.3 envelope"
    (let [tickets [{:frontmatter {:id "a" :status "open"} :body ""}
                   {:frontmatter {:id "b" :status "in_progress"} :body ""}]
          parsed  (envelope-of (output/ls-json tickets))]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (vector? (:data parsed)))
      (is (= 2 (count (:data parsed))))
      (is (= "a" (get-in parsed [:data 0 :id])))
      (is (= "b" (get-in parsed [:data 1 :id])))))
  (testing "empty list still emits a success envelope with :data []"
    (let [parsed (envelope-of (output/ls-json []))]
      (is (= true (:ok parsed)))
      (is (= [] (:data parsed))))))

(deftest dep-tree-json-envelope-test
  (testing "dep-tree-json wraps the nested tree in the v0.3 envelope"
    (let [tree {:id "kno-A"
                :ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                         :body ""}
                :children [{:id "kno-B"
                            :ticket {:frontmatter {:id "kno-B" :title "Beta" :status "open"}
                                     :body ""}
                            :children []}]}
          parsed (envelope-of (output/dep-tree-json tree))]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= "kno-A" (get-in parsed [:data :id])))
      (is (= "kno-B" (get-in parsed [:data :deps 0 :id]))))))

(deftest prime-json-envelope-test
  (testing "prime-json wraps the prime payload in the v0.3 envelope"
    (let [data {:project {:found? true :prefix "kno"
                          :name "knot" :live-count 1 :archive-count 0}
                :in-progress []
                :ready []
                :ready-truncated? false
                :ready-remaining 0}
          parsed (envelope-of (output/prime-json data))]
      (is (= 1 (:schema_version parsed)))
      (is (= true (:ok parsed)))
      (is (= "kno" (get-in parsed [:data :project :prefix])))
      (is (= [] (get-in parsed [:data :ready])))
      (is (= [] (get-in parsed [:data :in_progress])))
      (is (= false (get-in parsed [:data :ready_truncated]))))))
