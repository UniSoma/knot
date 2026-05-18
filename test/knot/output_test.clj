(ns knot.output-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.config :as config]
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

(deftest show-text-acceptance-test
  (testing "frontmatter :acceptance is synthesized as a `## Acceptance Criteria` checklist"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"
                                :acceptance [{:title "first" :done false}
                                             {:title "second" :done true}]}
                  :body "Body text.\n"}
          s (output/show-text ticket)]
      (is (str/includes? s "## Acceptance Criteria"))
      (is (str/includes? s "- [ ] first"))
      (is (str/includes? s "- [x] second"))))

  (testing "the synthesized AC section sits between the body and the inverse sections"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"
                                :acceptance [{:title "ship it" :done false}]}
                  :body "Body text.\n"}
          inverses {:blockers [(mk-resolved "kno-B" "Beta")]
                    :blocking [] :children [] :linked []}
          s (output/show-text ticket inverses)
          body-i (str/index-of s "Body text.")
          ac-i   (str/index-of s "## Acceptance Criteria")
          inv-i  (str/index-of s "## Blockers")]
      (is (every? some? [body-i ac-i inv-i]))
      (is (< body-i ac-i inv-i)
          "AC section comes after body, before inverse sections")))

  (testing "no :acceptance key in frontmatter -> no `## Acceptance Criteria` header"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"}
                  :body "Body text.\n"}
          s (output/show-text ticket)]
      (is (not (str/includes? s "## Acceptance Criteria")))))

  (testing "empty :acceptance vector -> no header"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"
                                :acceptance []}
                  :body "Body text.\n"}
          s (output/show-text ticket)]
      (is (not (str/includes? s "## Acceptance Criteria")))))

  (testing "single-arity show-text also synthesizes the AC section"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"
                                :acceptance [{:title "only" :done false}]}
                  :body ""}
          s (output/show-text ticket)]
      (is (str/includes? s "## Acceptance Criteria"))
      (is (str/includes? s "- [ ] only")))))

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
                               {:color? false :tty? true :width 70})
          plain (strip-ansi out)
          long-title "Critical pager outage during peak hours please address"]
      (is (not (str/includes? plain long-title))
          "with width=70 the long title should not appear in full")
      (doseq [line (str/split-lines plain)]
        (is (<= (count line) 70)
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

(deftest mode-role-test
  (testing "mode equal to :afk-mode → :afk"
    (is (= :afk
           (output/mode-role "afk" ["afk" "hitl"] "hitl" "afk"))))
  (testing "mode equal to :default-mode (and not afk) → :default"
    (is (= :default
           (output/mode-role "hitl" ["afk" "hitl"] "hitl" "afk"))))
  (testing "configured but neither afk nor default → :other"
    (is (= :other
           (output/mode-role "review" ["afk" "hitl" "review"] "hitl" "afk"))))
  (testing "unknown mode (not in :modes) → :other"
    (is (= :other
           (output/mode-role "wat" ["afk" "hitl"] "hitl" "afk"))))
  (testing ":afk-mode nil disables the :afk role — no mode resolves to :afk"
    (is (= :default
           (output/mode-role "hitl" ["afk" "hitl"] "hitl" nil)))
    (is (= :other
           (output/mode-role "afk" ["afk" "hitl"] "hitl" nil))))
  (testing "custom config: roles derive from config, not literal mode names"
    (let [modes ["agent" "human" "review"]
          default "human"
          afk "agent"]
      (is (= :afk     (output/mode-role "agent"  modes default afk)))
      (is (= :default (output/mode-role "human"  modes default afk)))
      (is (= :other   (output/mode-role "review" modes default afk))))))

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

(deftest ls-table-fallback-sources-from-config-defaults-test
  (testing "without status options, ls-table fallback follows knot.config/defaults — no baked literals"
    (with-redefs [config/defaults (constantly
                                   {:statuses          ["open" "active" "review" "closed"]
                                    :terminal-statuses #{"closed"}
                                    :active-status     "active"})]
      (let [out (output/ls-table custom-statuses-tickets
                                 {:color? true :tty? true :width 200})]
        (is (re-find #"\[33mactive" out)
            "active lane derived from config/defaults wraps in :yellow SGR (33)")
        (is (re-find #"\[2mclosed" out)
            "terminal lane derived from config/defaults wraps in :dim SGR (2)")
        (is (re-find #"\[36mopen" out)
            "first non-active non-terminal lane derived from config/defaults wraps in :cyan SGR (36)")))))

(def ^:private per-type-tickets
  [{:frontmatter {:id "kno-01abcd0020" :title "A bug" :status "open" :priority 2
                  :mode "hitl" :type "bug"     :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0021" :title "A feat" :status "open" :priority 2
                  :mode "hitl" :type "feature" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0022" :title "A task" :status "open" :priority 2
                  :mode "hitl" :type "task"    :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0023" :title "An epic" :status "open" :priority 2
                  :mode "hitl" :type "epic"    :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0024" :title "A chore" :status "open" :priority 2
                  :mode "hitl" :type "chore"   :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0025" :title "Unknown" :status "open" :priority 2
                  :mode "hitl" :type "weird"   :assignee "a"} :body ""}])

(deftest ls-table-type-color-test
  (testing "canonical types receive their per-value SGR; unknown falls back to faint"
    (let [out (output/ls-table per-type-tickets
                               {:color? true :tty? true :width 200
                                :statuses          ["open" "in_progress" "closed"]
                                :terminal-statuses #{"closed"}
                                :active-status     "in_progress"})]
      (is (re-find #"\[31mbug" out)
          "bug wraps in :red SGR (31)")
      (is (re-find #"\[32mfeature" out)
          "feature wraps in :green SGR (32)")
      (is (not (re-find #"\[[0-9;]+mtask" out))
          "task receives no SGR (plain)")
      (is (re-find #"\[35mepic" out)
          "epic wraps in :magenta SGR (35)")
      (is (re-find #"\[2mchore" out)
          "chore wraps in :faint SGR (2)")
      (is (re-find #"\[2mweird" out)
          "unknown type falls back to :faint SGR (2)"))))

(def ^:private per-priority-tickets
  [{:frontmatter {:id "kno-01abcd0030" :title "P0" :status "open" :priority 0
                  :mode "hitl" :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0031" :title "P1" :status "open" :priority 1
                  :mode "hitl" :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0032" :title "P2" :status "open" :priority 2
                  :mode "hitl" :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0033" :title "P3" :status "open" :priority 3
                  :mode "hitl" :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0034" :title "P4" :status "open" :priority 4
                  :mode "hitl" :type "task" :assignee "a"} :body ""}])

(deftest ls-table-priority-color-test
  (testing "priorities 0..4 receive per-ordinal SGR; 3 and 4 collapse to faint"
    (let [out (output/ls-table per-priority-tickets
                               {:color? true :tty? true :width 200
                                :statuses          ["open" "in_progress" "closed"]
                                :terminal-statuses #{"closed"}
                                :active-status     "in_progress"})]
      (is (re-find #"\[31;1m  0" out)
          "priority 0 wraps in :red :bold SGR (31;1)")
      (is (re-find #"\[33m  1" out)
          "priority 1 wraps in :yellow SGR (33)")
      (is (not (re-find #"\[[0-9;]+m  2" out))
          "priority 2 receives no SGR (plain — most common row)")
      (is (re-find #"\[2m  3" out)
          "priority 3 wraps in :faint SGR (2)")
      (is (re-find #"\[2m  4" out)
          "priority 4 wraps in :faint SGR (2)"))))

(def ^:private per-default-mode-tickets
  [{:frontmatter {:id "kno-01abcd0040" :title "Afk row" :status "open" :priority 2
                  :mode "afk"  :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0041" :title "Hitl row" :status "open" :priority 2
                  :mode "hitl" :type "task" :assignee "a"} :body ""}])

(deftest ls-table-default-modes-color-roles-test
  (testing "default modes (afk/hitl): afk → blue, hitl (default) → plain"
    (let [out (output/ls-table per-default-mode-tickets
                               {:color? true :tty? true :width 200
                                :statuses          ["open" "in_progress" "closed"]
                                :terminal-statuses #{"closed"}
                                :active-status     "in_progress"
                                :modes             ["afk" "hitl"]
                                :default-mode      "hitl"
                                :afk-mode          "afk"})]
      (is (re-find #"\[34mafk" out)
          ":afk-mode wraps in :blue SGR (34)")
      (is (not (re-find #"\[[0-9;]+mhitl" out))
          ":default-mode (hitl) receives no SGR (plain)"))))

(def ^:private per-custom-mode-tickets
  [{:frontmatter {:id "kno-01abcd0050" :title "Agent row" :status "open" :priority 2
                  :mode "agent"  :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0051" :title "Human row" :status "open" :priority 2
                  :mode "human"  :type "task" :assignee "a"} :body ""}
   {:frontmatter {:id "kno-01abcd0052" :title "Review row" :status "open" :priority 2
                  :mode "review" :type "task" :assignee "a"} :body ""}])

(deftest ls-table-custom-modes-color-roles-test
  (testing "custom modes resolve roles from config, not literal names"
    (let [out (output/ls-table per-custom-mode-tickets
                               {:color? true :tty? true :width 200
                                :statuses          ["open" "in_progress" "closed"]
                                :terminal-statuses #{"closed"}
                                :active-status     "in_progress"
                                :modes             ["agent" "human" "review"]
                                :default-mode      "human"
                                :afk-mode          "agent"})]
      (is (re-find #"\[34magent" out)
          ":afk-mode (\"agent\") wraps in :blue SGR (34)")
      (is (not (re-find #"\[[0-9;]+mhuman" out))
          ":default-mode (\"human\") receives no SGR (plain)")
      (is (re-find #"\[2mreview" out)
          "non-afk non-default mode (\"review\") wraps in :faint SGR (2)"))))

(deftest ls-table-no-mode-options-back-compat-test
  (testing "without mode options, ls-table falls back to config/defaults — afk row blue"
    (let [out (output/ls-table per-default-mode-tickets
                               {:color? true :tty? true :width 200})]
      (is (re-find #"\[34mafk" out)
          "default :afk-mode (\"afk\") wraps in :blue SGR (34) via config/defaults")
      (is (not (re-find #"\[[0-9;]+mhitl" out))
          "default :default-mode (\"hitl\") receives no SGR via config/defaults")))
  (testing "without mode options, fallback follows config/defaults — no baked literals"
    (with-redefs [config/defaults (constantly
                                   {:statuses          ["open" "in_progress" "closed"]
                                    :terminal-statuses #{"closed"}
                                    :active-status     "in_progress"
                                    :modes             ["agent" "human" "review"]
                                    :default-mode      "human"
                                    :afk-mode          "agent"})]
      (let [out (output/ls-table per-custom-mode-tickets
                                 {:color? true :tty? true :width 200})]
        (is (re-find #"\[34magent" out)
            ":afk-mode derived from config/defaults wraps \"agent\" in :blue SGR (34)")
        (is (re-find #"\[2mreview" out)
            "non-role mode (\"review\") derived from config/defaults wraps in :faint SGR (2)"))))
  (testing ":afk-mode nil disables the :afk role at the renderer"
    (let [out (output/ls-table per-default-mode-tickets
                               {:color? true :tty? true :width 200
                                :modes        ["afk" "hitl"]
                                :default-mode "hitl"
                                :afk-mode     nil})]
      (is (not (re-find #"\[34mafk" out))
          "with :afk-mode nil, no mode renders blue"))))

(def ^:private sample-ls-tickets-with-ac
  [{:frontmatter {:id "kno-01abcd0001"
                  :title "Has acceptance"
                  :status "open"
                  :priority 2
                  :mode "afk"
                  :type "task"
                  :assignee "alice"
                  :acceptance [{:title "x" :done true}
                               {:title "y" :done false}
                               {:title "z" :done false}]}
    :body ""}
   {:frontmatter {:id "kno-01abcd0002"
                  :title "No acceptance"
                  :status "in_progress"
                  :priority 0
                  :mode "hitl"
                  :type "bug"
                  :assignee "bob"}
    :body ""}])

(deftest ls-table-ac-column-shown-when-any-ticket-has-acceptance-test
  (testing "AC column header appears when at least one ticket has (seq :acceptance)"
    (let [out (output/ls-table sample-ls-tickets-with-ac {:color? false :tty? false})
          first-line (first (str/split-lines out))]
      (is (re-find #"\bAC\b" first-line)
          "AC header surfaces when any input ticket has acceptance")))

  (testing "AC column appears between ASSIGNEE and TITLE"
    (let [out (output/ls-table sample-ls-tickets-with-ac {:color? false :tty? false})
          first-line (first (str/split-lines out))
          ac-i    (str/index-of first-line "AC")
          title-i (str/index-of first-line "TITLE")
          assignee-i (str/index-of first-line "ASSIGNEE")]
      (is (every? some? [ac-i title-i assignee-i]))
      (is (< assignee-i ac-i title-i)
          "AC sits between ASSIGNEE and TITLE")))

  (testing "row with AC renders d/t (e.g. 1/3)"
    (let [out (output/ls-table sample-ls-tickets-with-ac {:color? false :tty? false})]
      (is (re-find #"\b1/3\b" out)
          "ticket with 1 of 3 done renders 1/3 in its AC cell")))

  (testing "row without AC renders \"-\""
    (let [out (output/ls-table sample-ls-tickets-with-ac {:color? false :tty? false})
          line (some (fn [l] (when (str/includes? l "kno-01abcd0002") l))
                     (str/split-lines out))]
      (is (some? line))
      (is (re-find #"bob\s+-\s+-\s+No acceptance" line)
          "AGE cell renders `-`, then AC cell renders `-`, before TITLE"))))

(deftest ls-table-ac-column-omitted-when-no-ticket-has-acceptance-test
  (testing "AC column header is absent when no input ticket has acceptance"
    (let [out (output/ls-table sample-ls-tickets {:color? false :tty? false})
          first-line (first (str/split-lines out))]
      (is (not (re-find #"\bAC\b" first-line))
          "no AC header when no ticket has acceptance"))))

(deftest ls-table-ac-column-on-closed-tickets-test
  (testing "force-closed terminal tickets render partial progress (audit signal)"
    (let [tickets [{:frontmatter {:id "kno-01force001"
                                  :title "Force-closed with open AC"
                                  :status "closed"
                                  :priority 2 :mode "afk" :type "task"
                                  :assignee "alice"
                                  :acceptance [{:title "x" :done true}
                                               {:title "y" :done true}
                                               {:title "z" :done false}
                                               {:title "w" :done false}
                                               {:title "v" :done false}]}
                    :body ""}]
          out (output/ls-table tickets {:color? false :tty? false})]
      (is (re-find #"\b2/5\b" out)
          "force-closed ticket with 2 of 5 AC done renders 2/5"))))

(deftest ls-table-age-column-header-test
  (testing "AGE column header appears in ls-table output"
    (let [out (output/ls-table sample-ls-tickets {:color? false :tty? false})
          first-line (first (str/split-lines out))]
      (is (re-find #"\bAGE\b" first-line)
          "AGE column header surfaces on every ls-table invocation"))))

(deftest ls-table-age-column-position-no-ac-test
  (testing "AGE sits between ASSIGNEE and TITLE when AC is absent"
    (let [out (output/ls-table sample-ls-tickets {:color? false :tty? false})
          first-line (first (str/split-lines out))
          assignee-i (str/index-of first-line "ASSIGNEE")
          age-i      (str/index-of first-line "AGE")
          title-i    (str/index-of first-line "TITLE")]
      (is (every? some? [assignee-i age-i title-i]))
      (is (< assignee-i age-i title-i)
          "AGE sits between ASSIGNEE and TITLE in the no-AC layout"))))

(deftest ls-table-age-column-position-with-ac-test
  (testing "AGE sits immediately to the left of AC when AC is present"
    (let [out (output/ls-table sample-ls-tickets-with-ac {:color? false :tty? false})
          first-line (first (str/split-lines out))
          assignee-i (str/index-of first-line "ASSIGNEE")
          age-i      (str/index-of first-line "AGE")
          ac-i       (str/index-of first-line "AC")
          title-i    (str/index-of first-line "TITLE")]
      (is (every? some? [assignee-i age-i ac-i title-i]))
      (is (< assignee-i age-i ac-i title-i)
          "AGE precedes AC; AC precedes TITLE"))))

(deftest ls-table-age-cell-renders-bucketed-string-test
  (testing "AGE cell renders bucketed string from per-ticket :age-days"
    (let [tickets [(assoc (first sample-ls-tickets) :age-days 3)
                   (assoc (second sample-ls-tickets) :age-days 21)]
          out   (output/ls-table tickets {:color? false :tty? false})
          lines (str/split-lines out)
          row1  (some #(when (str/includes? % "kno-01abcd0001") %) lines)
          row2  (some #(when (str/includes? % "kno-01abcd0002") %) lines)]
      (is (re-find #"\b3d\b" row1)
          "3 days renders as 3d (Nd bucket)")
      (is (re-find #"\b3w\b" row2)
          "21 days renders as 3w (Nw bucket)"))))

(deftest ls-table-age-cell-dash-when-missing-test
  (testing "AGE cell renders `-` when :age-days is absent from the ticket map"
    (let [out  (output/ls-table sample-ls-tickets {:color? false :tty? false})
          row  (some #(when (str/includes? % "kno-01abcd0001") %)
                     (str/split-lines out))]
      (is (some? row))
      (is (re-find #"alice\s+-\s+Fix login bug" row)
          "missing :age-days renders as `-` in the AGE slot before TITLE"))))

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

(deftest show-json-acceptance-test
  (testing ":acceptance frontmatter list is preserved verbatim under :data"
    (let [ticket {:frontmatter {:id "kno-A" :title "Alpha" :status "open"
                                :acceptance [{:title "first" :done false}
                                             {:title "second" :done true}]}
                  :body ""}
          parsed (json/parse-string (output/show-json ticket) true)
          ac     (get-in parsed [:data :acceptance])]
      (is (= [{:title "first"  :done false}
              {:title "second" :done true}]
             ac)
          "acceptance entries pass through unchanged through the JSON envelope"))))

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
  [{:keys [id status priority mode type title updated created]}]
  {:frontmatter (cond-> {:id id :title (or title "Untitled")}
                  status   (assoc :status status)
                  priority (assoc :priority priority)
                  mode     (assoc :mode mode)
                  type     (assoc :type type)
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
                                     :mode "afk" :priority 0 :type "bug"
                                     :title "Ready highest"
                                     :created "2026-04-28T09:00:00Z"})
                   (mk-prime-ticket {:id "kno-rd02" :status "open"
                                     :mode "hitl" :priority 2 :type "task"
                                     :title "Ready lower"
                                     :created "2026-04-27T09:00:00Z"})]
   :ready-truncated? false
   :ready-remaining 0})

(deftest format-age-days-test
  (testing "missing day-count renders as `-`"
    (is (= "-" (output/format-age-days nil))))
  (testing "<14 days renders as Nd"
    (is (= "0d"  (output/format-age-days 0)))
    (is (= "1d"  (output/format-age-days 1)))
    (is (= "13d" (output/format-age-days 13))))
  (testing "14d through 42d (6w) inclusive renders as Nw (floor by 7)"
    (is (= "2w" (output/format-age-days 14)))
    (is (= "2w" (output/format-age-days 15)) "floor: 15d still in 2w bucket")
    (is (= "6w" (output/format-age-days 42))))
  (testing ">42d renders as Nm (floor by 30)"
    (is (= "1m" (output/format-age-days 43)))
    (is (= "1m" (output/format-age-days 45)) "floor: 45d still in 1m bucket")
    (is (= "3m" (output/format-age-days 100)))))

(deftest prime-text-canonical-sections-test
  (testing "preamble paragraph appears at the top before all section headings"
    (let [out (output/prime-text sample-prime-data)
          first-section (or (str/index-of out "## ") (count out))
          preamble (subs out 0 first-section)]
      (is (pos? (count (str/trim preamble)))
          "preamble is a non-empty paragraph above the first heading")))

  (testing "project, in-progress, ready sections appear in canonical order"
    (let [out (output/prime-text sample-prime-data)
          p-i  (str/index-of out "## Project")
          ip-i (str/index-of out "## In Progress")
          rd-i (str/index-of out "## Ready")]
      (is (every? some? [p-i ip-i rd-i])
          "all three section headings present (preamble has no heading; cheatsheet retired)")
      (is (< p-i ip-i rd-i)
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
          end   (or (str/index-of out "## Recently Closed") (count out))
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
          end   (or (str/index-of out "## Recently Closed") (count out))
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

(deftest prime-text-ready-row-format-test
  (testing "ready row has 5 cols: id  type  mode  pri  title (type column inserted)"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## Ready")
          end   (or (str/index-of out "## Recently Closed") (count out))
          section (subs out start end)
          line (some (fn [l] (when (str/includes? l "kno-rd01") l))
                     (str/split-lines section))]
      (is (some? line))
      (is (re-find #"^kno-rd01\s+bug\s+afk\s+0\s+Ready highest" line)
          "row matches the canonical 5-col order: id type mode pri title")))

  (testing "ready row renders missing :type as `-`"
    (let [data (assoc sample-prime-data
                      :ready
                      [(mk-prime-ticket {:id "kno-notype"
                                         :status "open" :mode "afk" :priority 1
                                         :title "Has no type set"
                                         :created "2026-04-27T09:00:00Z"})])
          out (output/prime-text data)
          start (str/index-of out "## Ready")
          end   (or (str/index-of out "## Recently Closed") (count out))
          section (subs out start end)
          line (some (fn [l] (when (str/includes? l "kno-notype") l))
                     (str/split-lines section))]
      (is (some? line))
      (is (re-find #"kno-notype\s+-\s+afk\s+1\s+Has no type set" line)
          "missing type renders as `-` in the type column"))))

(deftest prime-text-ready-order-preserved-test
  (testing "the ready section emits tickets in the order supplied (caller controls the sort)"
    (let [out (output/prime-text sample-prime-data)
          start (str/index-of out "## Ready")
          end   (or (str/index-of out "## Recently Closed") (count out))
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
      (is (not (str/includes? out "## Commands"))
          "Commands cheatsheet retired — preamble + skill carry the verb mappings")
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

(deftest prime-text-hitl-preamble-rows-test
  (let [out (output/prime-text sample-prime-data)
        first-section (str/index-of out "## ")
        preamble (subs out 0 first-section)]
    (testing "hitl preamble surfaces all 8 canonical agent verbs in the intent table"
      (doseq [snippet ["knot ready" "knot show" "knot list" "knot start"
                       "knot add-note" "knot update" "knot close" "knot dep"]]
        (is (str/includes? preamble snippet)
            (str "hitl preamble missing intent for `" snippet "`"))))
    (testing "hitl preamble teaches the canonical `knot update` patch flags"
      (is (re-find #"knot update <id>.*--title.*--tags.*--priority" preamble)
          "the update row surfaces the four patch flags"))
    (testing "hitl preamble's closing pointer names the less-common ops"
      (is (str/includes? preamble "less-common ops")
          "modernized pointer phrasing 'less-common ops' present")
      (doseq [op ["info" "check" "link" "reopen" "--json" "partial-id"]]
        (is (str/includes? preamble op)
            (str "less-common ops pointer mentions `" op "`"))))
    (testing "hitl preamble drops the legacy 'lifecycle, graph ops' phrasing"
      (is (not (re-find #"lifecycle, graph ops" preamble))
          "the old skill-pointer prelude is gone"))))

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

(deftest prime-text-afk-preamble-shape-test
  (let [data (assoc sample-prime-data :mode "afk")
        out  (output/prime-text data)
        first-section (str/index-of out "## ")
        preamble (subs out 0 first-section)
        update-i   (str/index-of preamble "knot update")
        add-note-i (str/index-of preamble "knot add-note")
        close-i    (str/index-of preamble "knot close")]
    (testing "afk preamble surfaces the agent-write `knot update` step"
      (is (some? update-i) "afk preamble includes knot update")
      (is (re-find #"knot update <id>.*--priority" preamble)
          "afk preamble teaches the priority/tags update flags"))
    (testing "knot update lands between add-note and close in the autonomous flow"
      (is (every? some? [add-note-i update-i close-i])
          "all three steps present")
      (is (< add-note-i update-i close-i)
          "ordering: add-note → update → close"))
    (testing "afk preamble carries the `never knot edit` anti-pattern"
      (is (re-find #"(?i)never use `knot edit`|never `knot edit`" preamble)
          "anti-pattern names the forbidden command verbatim")
      (is (or (str/includes? preamble "TTY")
              (re-find #"(?i)\$editor|interactive" preamble))
          "anti-pattern explains *why* (interactive / no-TTY failure)"))
    (testing "afk skill pointer drops 'lifecycle' (subsumed by the explicit verbs)"
      (is (not (re-find #"lifecycle" preamble))
          "afk pointer no longer mentions 'lifecycle'")
      (doseq [topic ["graph ops" "JSON shapes" "partial-id"]]
        (is (str/includes? preamble topic)
            (str "afk pointer keeps `" topic "`"))))))

(deftest prime-text-afk-mode-config-driven-test
  (testing "renderer dispatches the AFK preamble on data-map :afk-mode, not the literal \"afk\""
    (let [data (assoc sample-prime-data :mode "robot" :afk-mode "robot")
          out  (output/prime-text data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"(?i)autonomous|agent" preamble)
          "custom :afk-mode value reaches the autonomous preamble")))

  (testing "with custom :afk-mode, the literal \"afk\" no longer triggers the agent preamble"
    (let [data (assoc sample-prime-data :mode "afk" :afk-mode "robot")
          out  (output/prime-text data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"what's next" preamble)
          "literal \"afk\" is no longer load-bearing — it falls through to the human preamble")))

  (testing ":afk-mode nil opts the project out of the agent preamble entirely"
    (let [data (assoc sample-prime-data :mode "afk" :afk-mode nil)
          out  (output/prime-text data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"what's next" preamble)
          "nil :afk-mode means no mode value picks the agent preamble")))

  (testing ":afk-mode comparison normalizes both sides (keyword/case/whitespace)"
    (let [data (assoc sample-prime-data :mode :ROBOT :afk-mode "  robot  ")
          out  (output/prime-text data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"(?i)autonomous|agent" preamble)
          "normalization is symmetric — keyword mode matches whitespace-padded :afk-mode"))))

(deftest prime-text-mentions-skill-test
  (testing "preamble mentions the `knot` skill so non-CC agents discover the canonical doc"
    (let [out (output/prime-text sample-prime-data)
          first-section (str/index-of out "## ")
          preamble (subs out 0 first-section)]
      (is (re-find #"(?i)skill" preamble)
          "preamble references the skill")
      (is (re-find #"(?i)knot.*skill|skill.*knot" preamble)
          "the skill mention is anchored to `knot`, not just any skill"))))

(deftest prime-text-in-progress-row-format-test
  (testing "in-progress row has 6 cols: id  type  mode  pri  age  title"
    (let [t (-> (mk-prime-ticket {:id "kno-ip-fmt" :status "in_progress"
                                  :mode "afk" :priority 1 :type "feature"
                                  :title "Six-col row"
                                  :updated "2026-04-28T10:00:00Z"})
                (assoc :age-days 8))
          data (assoc sample-prime-data :in-progress [t])
          out  (output/prime-text data)
          ip-start (str/index-of out "## In Progress")
          ip-end   (str/index-of out "## Ready")
          section  (subs out ip-start ip-end)
          line (some (fn [l] (when (str/includes? l "kno-ip-fmt") l))
                     (str/split-lines section))]
      (is (some? line))
      (is (re-find #"^kno-ip-fmt\s+feature\s+afk\s+1\s+8d\s+Six-col row" line)
          "row matches the canonical 6-col order: id type mode pri age title")))

  (testing "missing :age-days renders `-` in the age column"
    (let [t (mk-prime-ticket {:id "kno-no-age" :status "in_progress"
                              :mode "afk" :priority 0 :type "task"
                              :title "Has no updated"})
          data (assoc sample-prime-data :in-progress [t])
          out  (output/prime-text data)
          ip-start (str/index-of out "## In Progress")
          ip-end   (str/index-of out "## Ready")
          section  (subs out ip-start ip-end)
          line (some (fn [l] (when (str/includes? l "kno-no-age") l))
                     (str/split-lines section))]
      (is (some? line))
      (is (re-find #"^kno-no-age\s+task\s+afk\s+0\s+-\s+Has no updated" line)
          "missing age column renders as `-`")))

  (testing "[stale] prefix is gone from in-progress text rows (replaced by the age column)"
    (let [stale-ticket  (-> (mk-prime-ticket {:id "kno-stale" :status "in_progress"
                                              :mode "afk" :priority 1
                                              :title "Stalled work"
                                              :updated "2026-04-01T10:00:00Z"})
                            (assoc :prime-stale? true)
                            (assoc :age-days 35))
          data (assoc sample-prime-data :in-progress [stale-ticket])
          out  (output/prime-text data)]
      (is (not (str/includes? out "[stale]"))
          "[stale] prefix removed from text output (age column carries the signal)")
      (is (str/includes? out "5w")
          "35-day age renders as `5w` in the age column"))))

(deftest prime-text-in-progress-ac-slot-test
  (testing "in-progress row gains an AC slot (7 cols) when any ticket in the section has acceptance"
    (let [t (-> (mk-prime-ticket {:id "kno-ip-ac" :status "in_progress"
                                  :mode "afk" :priority 1 :type "feature"
                                  :title "Has AC"
                                  :updated "2026-04-28T10:00:00Z"})
                (assoc :age-days 3)
                (assoc-in [:frontmatter :acceptance]
                          [{:title "x" :done true}
                           {:title "y" :done false}]))
          data (assoc sample-prime-data :in-progress [t])
          out  (output/prime-text data)
          line (some (fn [l] (when (str/includes? l "kno-ip-ac") l))
                     (str/split-lines out))]
      (is (some? line))
      (is (re-find #"^kno-ip-ac\s+feature\s+afk\s+1\s+3d\s+1/2\s+Has AC" line)
          "row matches 7-col order: id type mode pri age ac title")))

  (testing "in-progress section with no AC tickets keeps the 6-col shape (no AC slot)"
    (let [t (-> (mk-prime-ticket {:id "kno-no-ac" :status "in_progress"
                                  :mode "afk" :priority 1 :type "task"
                                  :title "No AC"
                                  :updated "2026-04-28T10:00:00Z"})
                (assoc :age-days 1))
          data (assoc sample-prime-data :in-progress [t])
          out  (output/prime-text data)
          line (some (fn [l] (when (str/includes? l "kno-no-ac") l))
                     (str/split-lines out))]
      (is (some? line))
      (is (re-find #"^kno-no-ac\s+task\s+afk\s+1\s+1d\s+No AC" line)
          "row stays 6-col when no ticket in the section has AC")
      (is (not (re-find #"\b-\s+No AC" line))
          "no orphan `-` slot before the title when AC slot is omitted")))

  (testing "tickets without AC render a `-` slot when peers in the same section have AC"
    (let [t-ac    (-> (mk-prime-ticket {:id "kno-with-ac" :status "in_progress"
                                        :mode "afk" :priority 0 :type "task"
                                        :title "With AC"
                                        :updated "2026-04-28T10:00:00Z"})
                      (assoc :age-days 0)
                      (assoc-in [:frontmatter :acceptance]
                                [{:title "x" :done true}]))
          t-noac  (-> (mk-prime-ticket {:id "kno-bare" :status "in_progress"
                                        :mode "afk" :priority 1 :type "task"
                                        :title "Bare"
                                        :updated "2026-04-28T09:00:00Z"})
                      (assoc :age-days 0))
          data (assoc sample-prime-data :in-progress [t-ac t-noac])
          out  (output/prime-text data)
          bare-line (some (fn [l] (when (str/includes? l "kno-bare") l))
                          (str/split-lines out))]
      (is (some? bare-line))
      (is (re-find #"^kno-bare\s+task\s+afk\s+1\s+0d\s+-\s+Bare" bare-line)
          "ticket without AC renders `-` in the AC slot when section has AC peers"))))

(deftest prime-text-ready-to-close-section-test
  (let [t1 (-> (mk-prime-ticket {:id "kno-rtc01" :status "in_progress"
                                 :mode "afk" :priority 1 :type "task"
                                 :title "Done with all AC"
                                 :updated "2026-04-30T10:00:00Z"})
               (assoc :age-days 1)
               (assoc-in [:frontmatter :acceptance]
                         [{:title "x" :done true}
                          {:title "y" :done true}]))
        t2 (-> (mk-prime-ticket {:id "kno-rtc02" :status "in_progress"
                                 :mode "hitl" :priority 2 :type "feature"
                                 :title "Also done"
                                 :updated "2026-04-29T10:00:00Z"})
               (assoc :age-days 2)
               (assoc-in [:frontmatter :acceptance]
                         [{:title "a" :done true}]))]
    (testing "## Ready to close heading appears between ## In Progress and ## Ready"
      (let [data (assoc sample-prime-data :ready-to-close [t1])
            out  (output/prime-text data)
            ip-i  (str/index-of out "## In Progress")
            rtc-i (str/index-of out "## Ready to close")
            rd-i  (str/index-of out "## Ready\n")]
        (is (every? some? [ip-i rtc-i rd-i]))
        (is (< ip-i rtc-i rd-i)
            "Ready to close sits between In Progress and Ready")))

    (testing "section uses the in-progress line shape with the AC slot (id type mode pri age ac title)"
      (let [data (assoc sample-prime-data :ready-to-close [t1])
            out  (output/prime-text data)
            rtc-i (str/index-of out "## Ready to close")
            rtc-end (or (str/index-of out "## Ready\n" rtc-i) (count out))
            section (subs out rtc-i rtc-end)
            line (some (fn [l] (when (str/includes? l "kno-rtc01") l))
                       (str/split-lines section))]
        (is (some? line))
        (is (re-find #"^kno-rtc01\s+task\s+afk\s+1\s+1d\s+2/2\s+Done with all AC" line)
            "row matches 7-col order: id type mode pri age ac title")))

    (testing "section is uncapped (no `... +N more` footer)"
      (let [data (assoc sample-prime-data :ready-to-close [t1 t2])
            out  (output/prime-text data)
            rtc-i (str/index-of out "## Ready to close")
            rtc-end (str/index-of out "## Ready\n" rtc-i)
            section (subs out rtc-i rtc-end)]
        (is (not (re-find #"\+\d+\s+more" section))
            "no truncation footer in Ready to close section")))

    (testing "section is omitted entirely when no tickets match the predicate"
      (let [out (output/prime-text sample-prime-data)]
        (is (not (str/includes? out "## Ready to close"))
            "no heading when :ready-to-close is absent or empty"))
      (let [data (assoc sample-prime-data :ready-to-close [])
            out  (output/prime-text data)]
        (is (not (str/includes? out "## Ready to close"))
            "no heading when :ready-to-close is empty")))

    (testing "hitl nudge surfaces under the heading"
      (let [data (assoc sample-prime-data :ready-to-close [t1])
            out  (output/prime-text data)
            rtc-i (str/index-of out "## Ready to close")
            rtc-end (str/index-of out "## Ready\n" rtc-i)
            section (subs out rtc-i rtc-end)]
        (is (str/includes? section "All acceptance criteria are checked")
            "hitl nudge present")
        (is (re-find #"knot close <id>\s+--summary" section)
            "hitl nudge surfaces the close command")))

    (testing "afk nudge surfaces under the heading"
      (let [data (-> sample-prime-data
                     (assoc :ready-to-close [t1])
                     (assoc :mode "afk"))
            out  (output/prime-text data)
            rtc-i (str/index-of out "## Ready to close")
            rtc-end (str/index-of out "## Ready\n" rtc-i)
            section (subs out rtc-i rtc-end)]
        (is (str/includes? section "Close these before grabbing new tickets.")
            "afk nudge present")))))

(deftest prime-text-ready-ac-slot-test
  (testing "ready row gains an AC slot (6 cols) when any ticket in the section has acceptance"
    (let [t (-> (mk-prime-ticket {:id "kno-rd-ac" :status "open"
                                  :mode "afk" :priority 0 :type "bug"
                                  :title "Ready with AC"
                                  :created "2026-04-28T09:00:00Z"})
                (assoc-in [:frontmatter :acceptance]
                          [{:title "x" :done false}
                           {:title "y" :done false}
                           {:title "z" :done false}]))
          data (assoc sample-prime-data :ready [t])
          out  (output/prime-text data)
          line (some (fn [l] (when (str/includes? l "kno-rd-ac") l))
                     (str/split-lines out))]
      (is (some? line))
      (is (re-find #"^kno-rd-ac\s+bug\s+afk\s+0\s+0/3\s+Ready with AC" line)
          "row matches 6-col order: id type mode pri ac title")))

  (testing "ready section with no AC tickets keeps the 5-col shape"
    (let [out (output/prime-text sample-prime-data)
          rd-start (str/index-of out "## Ready")
          rd-end (or (str/index-of out "## Recently Closed") (count out))
          section (subs out rd-start rd-end)
          line (some (fn [l] (when (str/includes? l "kno-rd01") l))
                     (str/split-lines section))]
      (is (some? line))
      (is (re-find #"^kno-rd01\s+bug\s+afk\s+0\s+Ready highest" line)
          "row stays 5-col when no ready ticket has AC"))))

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
  (testing "## Recently Closed heading appears after Ready when entries exist"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-cl01"
                        :title "Shipped feature X"
                        :closed "2026-04-29T10:00:00Z"
                        :summary "shipped in #482"}])
          out (output/prime-text data)
          rd-i (str/index-of out "## Ready")
          rc-i (str/index-of out "## Recently Closed")]
      (is (every? some? [rd-i rc-i]))
      (is (< rd-i rc-i)
          "Recently Closed appears after Ready (it is the last section)")))

  (testing "each entry surfaces id and title"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-aaa" :title "Alpha shipped" :closed "2026-04-29T10:00:00Z"}
                       {:id "kno-bbb" :title "Beta shipped"  :closed "2026-04-28T10:00:00Z"}])
          out (output/prime-text data)
          rc-start (str/index-of out "## Recently Closed")
          section  (subs out rc-start (count out))]
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
          section  (subs out rc-start (count out))]
      (is (str/includes? section "shipped in PR #482"))))

  (testing "long multi-paragraph summary truncates at the first paragraph boundary"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-multi"
                        :title "Multi-paragraph close"
                        :closed "2026-04-29T10:00:00Z"
                        :summary "First paragraph is the headline.\n\nSecond paragraph has follow-up details that should NOT surface in prime."}])
          out (output/prime-text data)
          rc-start (str/index-of out "## Recently Closed")
          section  (subs out rc-start (count out))]
      (is (str/includes? section "First paragraph is the headline.")
          "first paragraph appears")
      (is (not (str/includes? section "Second paragraph has follow-up"))
          "everything after the first \\n\\n is dropped from prime text")
      (is (str/includes? section "(see knot show kno-multi)")
          "truncation marker pointing at full summary appended")))

  (testing "summary >280 chars hard-caps at 280 even with no paragraph break"
    (let [long-summary (apply str (repeat 30 "Lorem ipsum dolor sit amet, consectetur. "))
          data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-long"
                        :title "Long single paragraph"
                        :closed "2026-04-29T10:00:00Z"
                        :summary long-summary}])
          out (output/prime-text data)
          rc-start (str/index-of out "## Recently Closed")
          section  (subs out rc-start (count out))
          summary-line (some (fn [l] (when (str/starts-with? l "    ") l))
                             (str/split-lines section))]
      (is (some? summary-line) "summary line found")
      ;; Line is "    " (4 indent) + truncated body + " (see knot show kno-long)".
      ;; The body slice itself should be ≤ 280 chars.
      (is (str/includes? summary-line "(see knot show kno-long)")
          "hard-cap truncation also gets the see-knot-show marker")
      (let [marker " (see knot show kno-long)"
            body   (-> summary-line
                       (subs 4)
                       (str/replace marker ""))]
        (is (<= (count body) 280)
            (str "truncated body is ≤ 280 chars (was " (count body) ")")))))

  (testing "short single-paragraph summary is NOT truncated and gets no marker"
    (let [data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-short"
                        :title "Short"
                        :closed "2026-04-29T10:00:00Z"
                        :summary "Short summary, no paragraph break."}])
          out (output/prime-text data)
          rc-start (str/index-of out "## Recently Closed")
          section  (subs out rc-start (count out))]
      (is (str/includes? section "Short summary, no paragraph break.")
          "short summary surfaces in full")
      (is (not (str/includes? section "(see knot show"))
          "no truncation marker when no truncation happened")))

  (testing "JSON keeps the full multi-paragraph summary unchanged"
    (let [full-summary "First paragraph is the headline.\n\nSecond paragraph has follow-up details."
          data (assoc sample-prime-data
                      :recently-closed
                      [{:id "kno-multi"
                        :title "Multi-paragraph close"
                        :closed "2026-04-29T10:00:00Z"
                        :summary full-summary}])
          out (output/prime-json data)
          parsed (json/parse-string out true)
          entry (first (get-in parsed [:data :recently_closed]))]
      (is (= full-summary (:summary entry))
          "JSON consumers see the full untruncated summary")))

  (testing "the section is omitted entirely when :recently-closed is empty"
    (let [data (assoc sample-prime-data :recently-closed [])
          out (output/prime-text data)]
      (is (not (str/includes? out "## Recently Closed"))
          "no closes → no heading")))

  (testing "the section is omitted when :recently-closed is absent (backwards compat)"
    (let [out (output/prime-text sample-prime-data)]
      (is (not (str/includes? out "## Recently Closed"))
          "callers that don't supply :recently-closed get the prior behavior"))))

(deftest prime-text-commands-cheatsheet-removed-test
  (testing "## Commands cheatsheet is removed entirely in hitl mode (preamble + skill carry the load)"
    (let [out (output/prime-text sample-prime-data)]
      (is (not (str/includes? out "## Commands"))
          "hitl mode does not emit the Commands cheatsheet")))

  (testing "## Commands cheatsheet is removed entirely in afk mode"
    (let [data (assoc sample-prime-data :mode "afk")
          out  (output/prime-text data)]
      (is (not (str/includes? out "## Commands"))
          "afk mode does not emit the Commands cheatsheet"))))

(deftest prime-text-mode-conditioned-nudges-test
  (testing "afk mode drops the Ready section nudge entirely"
    (let [data (assoc sample-prime-data :mode "afk")
          out  (output/prime-text data)
          rd-start (str/index-of out "## Ready")
          rd-end   (or (str/index-of out "## Recently Closed") (count out))
          section  (subs out rd-start rd-end)]
      (is (not (re-find #"(?i)recommend|what's next|confirm" section))
          "afk's autonomous flow already covers picking the next ticket — no Ready nudge needed")))

  (testing "afk in-progress nudge is the agent-framed 'Finish your in-progress work...'"
    (let [data (assoc sample-prime-data :mode "afk")
          out  (output/prime-text data)
          ip-start (str/index-of out "## In Progress")
          ip-end   (str/index-of out "## Ready")
          section  (subs out ip-start ip-end)]
      (is (str/includes? section "Finish your in-progress work before grabbing new tickets.")
          "afk in-progress nudge is the new agent-framed copy")
      (is (not (re-find #"\buser\b" section))
          "afk in-progress nudge removes the 'user' reference (no human in the loop)")))

  (testing "hitl mode keeps the existing nudges intact"
    (let [out (output/prime-text sample-prime-data)
          ip-start (str/index-of out "## In Progress")
          ip-end   (str/index-of out "## Ready")
          ip-section (subs out ip-start ip-end)
          rd-start (str/index-of out "## Ready")
          rd-end   (or (str/index-of out "## Recently Closed") (count out))
          rd-section (subs out rd-start rd-end)]
      (is (str/includes? ip-section "Resume here if the user picks up mid-stream.")
          "hitl in-progress nudge unchanged")
      (is (str/includes? rd-section "If asked")
          "hitl ready nudge unchanged"))))

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
          ;; Stop at the next `## ` heading after Ready (Recently Closed) so
          ;; the nudge match is scoped to the Ready section body.
          next-sec (or (str/index-of out "## Recently Closed" start)
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

(deftest prime-json-ready-to-close-array-test
  (let [t (-> (mk-prime-ticket {:id "kno-rtc01" :status "in_progress"
                                :mode "afk" :priority 1 :type "task"
                                :title "Done"
                                :updated "2026-04-30T10:00:00Z"})
              (assoc-in [:frontmatter :acceptance]
                        [{:title "x" :done true}]))]
    (testing "prime-json envelope carries a ready_to_close array parallel to in_progress, ready, recently_closed"
      (let [data (assoc sample-prime-data :ready-to-close [t])
            out  (output/prime-json data)
            parsed (json/parse-string out true)]
        (is (vector? (get-in parsed [:data :ready_to_close])))
        (is (= 1 (count (get-in parsed [:data :ready_to_close]))))))

    (testing "ready_to_close uses the same per-ticket projection as in_progress (id, title, status, type, priority, mode, updated)"
      (let [data (assoc sample-prime-data :ready-to-close [t])
            parsed (json/parse-string (output/prime-json data) true)
            entry (first (get-in parsed [:data :ready_to_close]))]
        (is (= "kno-rtc01" (:id entry)))
        (is (= "Done" (:title entry)))
        (is (= "in_progress" (:status entry)))
        (is (= "task" (:type entry)))
        (is (= 1 (:priority entry)))
        (is (= "afk" (:mode entry)))
        (is (= "2026-04-30T10:00:00Z" (:updated entry)))))

    (testing "no acceptance_progress derived field on per-ticket JSON projections"
      (let [data (assoc sample-prime-data :ready-to-close [t])
            out  (output/prime-json data)]
        (is (not (str/includes? out "acceptance_progress"))
            "no derived AC progress field — raw AC isn't in this projection either")))

    (testing "ready_to_close defaults to an empty array when no tickets match"
      (let [parsed (json/parse-string (output/prime-json sample-prime-data) true)]
        (is (= [] (get-in parsed [:data :ready_to_close])))))))

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

(deftest jsonify-vector-defaults-test
  (let [vector-keys [:tags :deps :links :external_refs]
        bare-ticket {:frontmatter {:id "kno-A" :status "open"} :body ""}]
    (testing "show-json injects [] for absent vector keys"
      (let [parsed (envelope-of (output/show-json bare-ticket))]
        (doseq [k vector-keys]
          (is (= [] (get-in parsed [:data k]))
              (str "show-json must emit " k " as [] when absent")))))
    (testing "ls-json injects [] for absent vector keys, per entry"
      (let [parsed (envelope-of (output/ls-json [bare-ticket]))]
        (doseq [k vector-keys]
          (is (= [] (get-in parsed [:data 0 k]))
              (str "ls-json must emit " k " as [] when absent")))))
    (testing "touched-ticket-json injects [] for absent vector keys"
      (let [parsed (envelope-of (output/touched-ticket-json bare-ticket))]
        (doseq [k vector-keys]
          (is (= [] (get-in parsed [:data k]))
              (str "touched-ticket-json must emit " k " as [] when absent")))))
    (testing "touched-tickets-json injects [] for absent vector keys, per entry"
      (let [parsed (envelope-of (output/touched-tickets-json [bare-ticket]))]
        (doseq [k vector-keys]
          (is (= [] (get-in parsed [:data 0 k]))
              (str "touched-tickets-json must emit " k " as [] when absent")))))
    (testing "present vector values pass through unchanged (no override)"
      (let [full-ticket {:frontmatter {:id "kno-A"
                                       :status "open"
                                       :tags ["x" "y"]
                                       :deps ["kno-B"]
                                       :links ["kno-C"]
                                       :external_refs ["JIRA-1"]}
                         :body ""}
            parsed (envelope-of (output/show-json full-ticket))]
        (is (= ["x" "y"]   (get-in parsed [:data :tags])))
        (is (= ["kno-B"]   (get-in parsed [:data :deps])))
        (is (= ["kno-C"]   (get-in parsed [:data :links])))
        (is (= ["JIRA-1"]  (get-in parsed [:data :external_refs])))))
    (testing "optional scalars stay absent (no null emitted)"
      (let [parsed (envelope-of (output/show-json bare-ticket))]
        (is (not (contains? (:data parsed) :parent)))
        (is (not (contains? (:data parsed) :assignee)))
        (is (not (contains? (:data parsed) :closed)))))))

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

(def ^:private full-info-data
  {:project {:knot_version "0.2.0"
             :name nil
             :prefix "kno"
             :config_present false}
   :paths {:cwd "/cwd"
           :project_root "/root"
           :config_path "/root/.knot.edn"
           :tickets_dir ".tickets"
           :tickets_path "/root/.tickets"
           :archive_path "/root/.tickets/archive"}
   :defaults {:default_assignee nil
              :effective_create_assignee "alice"
              :default_type "task"
              :default_priority 2
              :default_mode "hitl"}
   :allowed_values {:statuses ["open" "in_progress" "closed"]
                    :active_status "in_progress"
                    :terminal_statuses ["closed"]
                    :types ["bug" "feature" "task"]
                    :modes ["afk" "hitl"]
                    :afk_mode "afk"
                    :priority_range {:min 0 :max 4}}
   :counts {:live_count 5
            :archive_count 3
            :total_count 8}})

(deftest info-text-rendering-test
  (testing "info-text emits the five fixed section headings"
    (let [s (output/info-text full-info-data)]
      (is (str/includes? s "Project"))
      (is (str/includes? s "Paths"))
      (is (str/includes? s "Defaults"))
      (is (str/includes? s "Allowed Values"))
      (is (str/includes? s "Counts"))))

  (testing "scalar values render as `Label: value` lines, lists as comma-separated"
    (let [s (output/info-text full-info-data)]
      (is (str/includes? s "Knot version: 0.2.0"))
      (is (str/includes? s "Prefix: kno"))
      (is (str/includes? s "CWD: /cwd"))
      (is (str/includes? s "Project root: /root"))
      (is (str/includes? s "Config path: /root/.knot.edn"))
      (is (str/includes? s "Tickets dir: .tickets"))
      (is (str/includes? s "Tickets path: /root/.tickets"))
      (is (str/includes? s "Archive path: /root/.tickets/archive"))
      (is (str/includes? s "Default type: task"))
      (is (str/includes? s "Default priority: 2"))
      (is (str/includes? s "Default mode: hitl"))
      (is (str/includes? s "Statuses: open, in_progress, closed")
          "lists render as a single comma-separated line preserving order")
      (is (str/includes? s "Active status: in_progress"))
      (is (str/includes? s "Terminal statuses: closed"))
      (is (str/includes? s "Types: bug, feature, task"))
      (is (str/includes? s "Modes: afk, hitl"))
      (is (str/includes? s "Afk mode: afk")
          "the autonomous-mode label surfaces in the Allowed Values block")
      (is (str/includes? s "Priority range: 0-4"))
      (is (str/includes? s "Live count: 5"))
      (is (str/includes? s "Archive count: 3"))
      (is (str/includes? s "Total count: 8"))))

  (testing "unset scalars render as (none); config_present renders yes/no"
    (let [s (output/info-text full-info-data)]
      (is (str/includes? s "Name: (none)") "nil scalar renders as (none)")
      (is (str/includes? s "Default assignee: (none)"))
      (is (str/includes? s "Config present: no")
          "config_present:false renders as 'no'")
      (is (str/includes? s "Effective create assignee: alice"))))

  (testing "afk_mode nil opt-out renders as (none)"
    (let [s (output/info-text (assoc-in full-info-data
                                        [:allowed_values :afk_mode] nil))]
      (is (str/includes? s "Afk mode: (none)")
          "nil :afk_mode round-trips into a (none) marker")))

  (testing "config_present:true renders as yes"
    (let [s (output/info-text (assoc-in full-info-data [:project :config_present] true))]
      (is (str/includes? s "Config present: yes"))))

  (testing "no ANSI escape codes appear in plain text output"
    (let [s (output/info-text full-info-data)]
      (is (not (str/includes? s "["))
          "info-text never emits ANSI color"))))

