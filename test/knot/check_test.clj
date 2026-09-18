(ns knot.check-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.check :as check]
            [knot.cli :as cli]
            [knot.store :as store]))

(defmacro ^:private with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(defn- droot
  "The default document corpus root for a sandbox project. `:docs-dir` is
   nil in these tests, so this is what `store/docs-root` resolves to."
  [tmp]
  (store/docs-root tmp ".tickets" nil))

(def ^:private default-config
  {:statuses          ["open" "in_progress" "closed"]
   :terminal-statuses #{"closed"}
   :active-status     "open"
   :types             ["task" "bug" "feature" "chore" "spike"]
   :modes             ["afk" "hitl"]
   :doc-types         ["spec" "plan" "other"]
   :default-doc-type  "other"})

(defn- ticket
  "Test helper: build a {:frontmatter ...} map. Extras override defaults.
   Defaults to live (non-archived) location."
  [id status deps & {:as extras}]
  {:frontmatter (merge {:id id :status status :deps deps} extras)
   :body        (str "# " id "\n")
   :path        (str "/tmp/fake/" id ".md")
   :archived?   false})

(defn- archived-ticket
  "Test helper: like ticket, but tagged as living in archive/."
  [id status deps & {:as extras}]
  {:frontmatter (merge {:id id :status status :deps deps} extras)
   :body        (str "# " id "\n")
   :path        (str "/tmp/fake/archive/" id ".md")
   :archived?   true})

(defn- run-with
  "Run with default-config + sane scanned counts."
  [tickets & {:keys [config parse-errors] :or {config default-config}}]
  (check/run {:tickets      tickets
              :parse-errors (or parse-errors [])
              :config       config
              :scanned      {:live (count tickets) :archive 0}}))

(defn- issues-of
  "Filter issues from a result by code."
  [result code]
  (filterv #(= code (:code %)) (:issues result)))

(deftest run-flags-acceptance-invalid
  (testing "well-formed :acceptance entries produce no issues"
    (let [tickets [(ticket "a" "open" []
                           :acceptance [{:title "x" :done false}
                                        {:title "y" :done true}])]
          result  (run-with tickets)]
      (is (empty? (issues-of result :acceptance_invalid)))))

  (testing "missing :acceptance key produces no issues (the field is optional)"
    (let [tickets [(ticket "a" "open" [])]
          result  (run-with tickets)]
      (is (empty? (issues-of result :acceptance_invalid)))))

  (testing ":acceptance must be a sequential collection (string flagged)"
    (let [tickets [(ticket "a" "open" [] :acceptance "oops")]
          result  (run-with tickets)
          issues  (issues-of result :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= :error (:severity (first issues))))
      (is (= ["a"] (:ids (first issues))))))

  (testing "entries that are not maps are flagged"
    (let [tickets [(ticket "a" "open" [] :acceptance ["just a string"])]
          result  (run-with tickets)
          issues  (issues-of result :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))))

  (testing "entry missing :title is flagged"
    (let [tickets [(ticket "a" "open" [] :acceptance [{:done false}])]
          issues  (issues-of (run-with tickets) :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= :title (:field (first issues))))))

  (testing "entry missing :done is flagged"
    (let [tickets [(ticket "a" "open" [] :acceptance [{:title "x"}])]
          issues  (issues-of (run-with tickets) :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= :done (:field (first issues))))))

  (testing "non-string :title is flagged"
    (let [tickets [(ticket "a" "open" [] :acceptance [{:title 42 :done false}])]
          issues  (issues-of (run-with tickets) :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= :title (:field (first issues))))))

  (testing "blank :title is flagged"
    (let [tickets [(ticket "a" "open" [] :acceptance [{:title "" :done false}])]
          issues  (issues-of (run-with tickets) :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= :title (:field (first issues))))))

  (testing "non-boolean :done is flagged"
    (let [tickets [(ticket "a" "open" []
                           :acceptance [{:title "x" :done "yes"}])]
          issues  (issues-of (run-with tickets) :acceptance_invalid)]
      (is (= 1 (count issues)))
      (is (= :done (:field (first issues))))))

  (testing "multiple bad entries on one ticket each yield their own issue"
    (let [tickets [(ticket "a" "open" []
                           :acceptance [{:title 1 :done false}
                                        {:title "ok" :done "no"}])]
          issues  (issues-of (run-with tickets) :acceptance_invalid)]
      (is (= 2 (count issues))))))

(deftest run-empty-project
  (testing "no tickets -> no issues, scanned counts pass through"
    (let [result (check/run {:tickets [] :config default-config
                             :scanned {:live 0 :archive 0}})]
      (is (= [] (:issues result)))
      (is (= {:live 0 :archive 0 :docs 0} (:scanned result))))))

(deftest run-detects-dep-cycle
  (testing "single back-edge cycle yields one dep_cycle error issue"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" ["a"])]
          result  (run-with tickets)
          issues  (issues-of result :dep_cycle)]
      (is (= 1 (count issues)) "exactly one cycle issue")
      (let [issue (first issues)]
        (is (= :error (:severity issue)))
        (is (= :dep_cycle (:code issue)))
        (is (vector? (:ids issue)) ":ids must always be a vector")
        (is (= (first (:ids issue)) (last (:ids issue)))
            "cycle path [v ... v] starts and ends with the same id")
        (is (= #{"a" "b"} (set (butlast (:ids issue))))
            "cycle covers both ids")
        (is (string? (:message issue)) "message is human-readable"))
      (is (= {:live 2 :archive 0 :docs 0} (:scanned result)))))

  (testing "self-loop yields a single-id cycle"
    (let [tickets [(ticket "a" "open" ["a"])]
          issues  (issues-of (run-with tickets) :dep_cycle)]
      (is (= 1 (count issues)))
      (is (= ["a" "a"] (:ids (first issues))))))

  (testing "pure DAG produces no dep_cycle issues"
    (let [tickets [(ticket "a" "open" ["b"])
                   (ticket "b" "open" [])]]
      (is (= [] (issues-of (run-with tickets) :dep_cycle))))))

(deftest invalid-status-test
  (testing "ticket with status not in :statuses -> invalid_status error"
    (let [tickets [(ticket "a" "wat" [] :title "T")]
          issues  (issues-of (run-with tickets) :invalid_status)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :error (:severity issue)))
        (is (= :invalid_status (:code issue)))
        (is (= ["a"] (:ids issue)))
        (is (= :status (:field issue)))
        (is (= "wat" (:value issue)))
        (is (string? (:message issue))))))

  (testing "valid statuses produce no invalid_status issues"
    (let [tickets [(ticket "a" "open" [] :title "T")
                   (ticket "b" "in_progress" [] :title "T")
                   (ticket "c" "closed" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :invalid_status))))))

(deftest invalid-type-test
  (testing "ticket with type not in :types -> invalid_type error"
    (let [tickets [(ticket "a" "open" [] :title "T" :type "weird")]
          issues  (issues-of (run-with tickets) :invalid_type)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :error (:severity issue)))
        (is (= ["a"] (:ids issue)))
        (is (= :type (:field issue)))
        (is (= "weird" (:value issue))))))

  (testing "missing :type is allowed (not all tickets have one)"
    (let [tickets [(ticket "a" "open" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :invalid_type)))))

  (testing "valid types produce no invalid_type issues"
    (let [tickets [(ticket "a" "open" [] :title "T" :type "task")
                   (ticket "b" "open" [] :title "T" :type "feature")]]
      (is (= [] (issues-of (run-with tickets) :invalid_type))))))

(deftest invalid-mode-test
  (testing "ticket with mode not in :modes -> invalid_mode error"
    (let [tickets [(ticket "a" "open" [] :title "T" :mode "weird")]
          issues  (issues-of (run-with tickets) :invalid_mode)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= ["a"] (:ids issue)))
        (is (= :mode (:field issue)))
        (is (= "weird" (:value issue))))))

  (testing "missing :mode is allowed"
    (let [tickets [(ticket "a" "open" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :invalid_mode)))))

  (testing "valid modes produce no invalid_mode issues"
    (let [tickets [(ticket "a" "open" [] :title "T" :mode "afk")
                   (ticket "b" "open" [] :title "T" :mode "hitl")]]
      (is (= [] (issues-of (run-with tickets) :invalid_mode))))))

(deftest invalid-priority-test
  (testing "priority outside 0..4 -> invalid_priority error"
    (let [tickets [(ticket "a" "open" [] :title "T" :priority 5)
                   (ticket "b" "open" [] :title "T" :priority -1)]
          issues  (issues-of (run-with tickets) :invalid_priority)
          ids     (set (mapcat :ids issues))]
      (is (= 2 (count issues)))
      (is (= #{"a" "b"} ids))
      (is (every? #(= :priority (:field %)) issues))))

  (testing "priority of non-integer -> invalid_priority error"
    (let [tickets [(ticket "a" "open" [] :title "T" :priority "high")]
          issues  (issues-of (run-with tickets) :invalid_priority)]
      (is (= 1 (count issues)))
      (is (= "high" (:value (first issues))))))

  (testing "missing :priority is allowed"
    (let [tickets [(ticket "a" "open" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :invalid_priority)))))

  (testing "priority 0..4 produces no issues"
    (let [tickets (for [p [0 1 2 3 4]]
                    (ticket (str "p" p) "open" [] :title "T" :priority p))]
      (is (= [] (issues-of (run-with tickets) :invalid_priority))))))

(deftest missing-required-field-test
  (testing "missing :title -> missing_required_field error"
    (let [tickets [{:frontmatter {:id "a" :status "open"}
                    :body "" :path "/x/a.md"}]
          issues  (issues-of (run-with tickets) :missing_required_field)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))
      (is (= :title (:field (first issues))))))

  (testing "missing :status -> missing_required_field error"
    (let [tickets [{:frontmatter {:id "a" :title "T"}
                    :body "" :path "/x/a.md"}]
          issues  (issues-of (run-with tickets) :missing_required_field)]
      (is (= 1 (count issues)))
      (is (= :status (:field (first issues))))))

  (testing "missing :id -> missing_required_field error, :ids is empty vector"
    (let [tickets [{:frontmatter {:title "T" :status "open"}
                    :body "" :path "/x/no-id.md"}]
          issues  (issues-of (run-with tickets) :missing_required_field)]
      (is (= 1 (count issues)))
      (is (= [] (:ids (first issues))) ":ids must be a vector even when empty")
      (is (= :id (:field (first issues))))
      (is (string? (:path (first issues))) ":path locates a ticket without :id")))

  (testing "blank title is treated as missing"
    (let [tickets [{:frontmatter {:id "a" :title "" :status "open"}
                    :body "" :path "/x/a.md"}]
          issues  (issues-of (run-with tickets) :missing_required_field)]
      (is (= 1 (count issues)))
      (is (= :title (:field (first issues))))))

  (testing "all-required-present produces no issues"
    (let [tickets [(ticket "a" "open" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :missing_required_field))))))

(deftest terminal-outside-archive-test
  (testing "terminal-status ticket living outside archive/ -> error"
    (let [tickets [(ticket "a" "closed" [] :title "T")]
          issues  (issues-of (run-with tickets) :terminal_outside_archive)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))
      (is (string? (:path (first issues))))))

  (testing "non-terminal-status ticket living inside archive/ -> error"
    (let [tickets [(archived-ticket "a" "open" [] :title "T")]
          issues  (issues-of (run-with tickets) :terminal_outside_archive)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))))

  (testing "terminal status in archive/ produces no issues"
    (let [tickets [(archived-ticket "a" "closed" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :terminal_outside_archive)))))

  (testing "non-terminal status outside archive/ produces no issues"
    (let [tickets [(ticket "a" "open" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :terminal_outside_archive))))))

(deftest unknown-id-test
  (testing "dangling :deps reference -> unknown_id error owned by holder"
    (let [tickets [(ticket "a" "open" ["ghost"] :title "T")]
          issues  (issues-of (run-with tickets) :unknown_id)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))) ":ids names the holder, not the missing target")
      (is (re-find #"ghost" (:message (first issues))))))

  (testing "dangling :links reference -> unknown_id error"
    (let [tickets [(ticket "a" "open" [] :title "T" :links ["nope"])]
          issues  (issues-of (run-with tickets) :unknown_id)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))
      (is (re-find #"nope" (:message (first issues))))))

  (testing "dangling :parent reference -> unknown_id error"
    (let [tickets [(ticket "a" "open" [] :title "T" :parent "missing")]
          issues  (issues-of (run-with tickets) :unknown_id)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))
      (is (re-find #"missing" (:message (first issues))))))

  (testing "every reference resolved -> no unknown_id"
    (let [tickets [(ticket "a" "open" ["b"] :title "T" :links ["c"] :parent "d")
                   (ticket "b" "open" [] :title "T")
                   (ticket "c" "open" [] :title "T")
                   (ticket "d" "open" [] :title "T")]]
      (is (= [] (issues-of (run-with tickets) :unknown_id)))))

  (testing "multiple dangling refs from one holder -> one issue per missing target"
    (let [tickets [(ticket "a" "open" ["x" "y"] :title "T")]
          issues  (issues-of (run-with tickets) :unknown_id)
          msgs    (map :message issues)]
      (is (= 2 (count issues)))
      (is (every? #(= ["a"] %) (map :ids issues)))
      (is (some #(re-find #"\bx\b" %) msgs))
      (is (some #(re-find #"\by\b" %) msgs))))

  (testing "holder missing :id -> unknown_id skipped (missing_required_field surfaces the bare id)"
    (let [bad     (-> (ticket "ignored" "open" ["ghost"] :title "T")
                      (update :frontmatter dissoc :id))
          result  (run-with [bad])
          unknown (issues-of result :unknown_id)
          missing (issues-of result :missing_required_field)]
      (is (= [] unknown)
          "no :ids [nil] issues — the unknown-id check is skipped without a holder id")
      (is (some #(= :id (:field %)) missing)
          "missing-required-field still surfaces the absent :id"))))

(deftest frontmatter-parse-error-test
  (testing "each parse-error path becomes a frontmatter_parse_error issue"
    (let [parse-errors [{:path "/x/broken.md"  :message "yaml at line 3"}
                        {:path "/x/broken2.md" :message "missing fence"}]
          result       (run-with [] :parse-errors parse-errors)
          issues       (issues-of result :frontmatter_parse_error)]
      (is (= 2 (count issues)))
      (is (every? #(= :error (:severity %)) issues))
      (is (every? #(= [] (:ids %)) issues)
          ":ids is an empty vector — id is unknown when parsing failed")
      (is (= #{"/x/broken.md" "/x/broken2.md"}
             (set (map :path issues))))
      (is (every? #(string? (:message %)) issues))))

  (testing "no parse errors -> no frontmatter_parse_error issues"
    (is (= [] (issues-of (run-with []) :frontmatter_parse_error)))))

(deftest invalid-active-status-test
  (testing "active-status not in :statuses -> single global error issue"
    (let [config {:statuses          ["open" "active" "closed"]
                  :terminal-statuses #{"closed"}
                  :active-status     "in_progress"
                  :types             ["task"]
                  :modes             ["afk" "hitl"]}
          issues (issues-of (run-with [] :config config) :invalid_active_status)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :error (:severity issue)))
        (is (= [] (:ids issue)) "global issue: ids is empty vector")
        (is (string? (:message issue)))
        (is (re-find #"in_progress" (:message issue))))))

  (testing "valid active-status -> no invalid_active_status issue"
    (is (= [] (issues-of (run-with [] :config default-config)
                         :invalid_active_status))))

  (testing "active-status in :terminal-statuses -> issue surfaces"
    (let [config {:statuses          ["open" "in_progress" "closed"]
                  :terminal-statuses #{"closed"}
                  :active-status     "closed"
                  :types             ["task"]
                  :modes             ["afk" "hitl"]}
          issues (issues-of (run-with [] :config config) :invalid_active_status)]
      (is (= 1 (count issues))))))

(deftest filter-issues-test
  (let [issues [{:severity :error   :code :dep_cycle  :ids ["a" "b" "a"] :message "x"}
                {:severity :error   :code :unknown_id :ids ["a"]         :message "y"}
                {:severity :warning :code :stale      :ids ["c"]         :message "z"}]]

    (testing "no filter spec -> all issues pass"
      (is (= issues (check/filter-issues issues nil)))
      (is (= issues (check/filter-issues issues {}))))

    (testing "single severity filter -> only matching severity"
      (let [filtered (check/filter-issues issues {:severity #{:error}})]
        (is (= 2 (count filtered)))
        (is (every? #(= :error (:severity %)) filtered))))

    (testing "single code filter -> only matching code"
      (let [filtered (check/filter-issues issues {:code #{:dep_cycle}})]
        (is (= 1 (count filtered)))
        (is (= :dep_cycle (:code (first filtered))))))

    (testing "multiple codes -> OR within :code (any of)"
      (let [filtered (check/filter-issues issues {:code #{:dep_cycle :unknown_id}})]
        (is (= 2 (count filtered)))))

    (testing "severity AND code -> AND across, OR within"
      (let [filtered (check/filter-issues issues {:severity #{:error}
                                                  :code     #{:unknown_id :stale}})]
        (is (= 1 (count filtered)))
        (is (= :unknown_id (:code (first filtered))))))

    (testing "unknown code is accepted silently (open enum) and matches nothing"
      (let [filtered (check/filter-issues issues {:code #{:does_not_exist}})]
        (is (= 0 (count filtered)))))))

(defn- sort-key
  "Mirrors the sort order asserted by the spec: severity-desc (error
   before warning), code-asc, first-id-asc, message-asc."
  [{:keys [severity code ids message]}]
  [(case severity :error 0 :warning 1 9)
   (name code)
   (or (first ids) "")
   (or message "")])

(deftest run-sorts-issues-test
  (testing "issues are sorted: severity desc -> code asc -> first-id asc -> message asc"
    (let [tickets [(ticket "z" "wat"   [] :title "T")
                   (ticket "a" "bogus" [] :title "T")
                   (ticket "b" "open"  ["ghost"] :title "T")]
          issues  (:issues (run-with tickets))]
      (is (= issues (vec (sort-by sort-key issues)))
          "issues already in canonical order"))))

(deftest run-ids-filter-test
  (testing "ids-filter narrows per-ticket tier; globals still run"
    (let [tickets [(ticket "a" "wat"  [] :title "T")
                   (ticket "b" "weird" [] :title "T")
                   (ticket "c" "open" ["c"] :title "T")]
          result   (check/run {:tickets tickets
                               :config  default-config
                               :scanned {:live 3 :archive 0}
                               :ids-filter #{"a"}})
          codes    (set (map :code (:issues result)))]
      (is (contains? codes :invalid_status) "ticket a still inspected")
      (is (contains? codes :dep_cycle)
          "global dep_cycle on c still surfaces despite ids-filter")
      (is (= 1 (count (filterv #(= :invalid_status (:code %)) (:issues result))))
          "only ticket a contributes invalid_status; b is filtered out")))

  (testing "empty ids-filter (nil) -> no narrowing"
    (let [tickets [(ticket "a" "wat"   [] :title "T")
                   (ticket "b" "weird" [] :title "T")]
          result  (check/run {:tickets tickets :config default-config
                              :scanned {:live 2 :archive 0}})]
      (is (= 2 (count (filterv #(= :invalid_status (:code %)) (:issues result))))))))

(deftest validate-filter-spec-test
  (testing "nil spec -> nil"
    (is (nil? (check/validate-filter-spec nil))))

  (testing "valid severities and codes -> nil (no error)"
    (is (nil? (check/validate-filter-spec {:severity #{:error :warning}
                                           :code     #{:dep_cycle :anything}}))))

  (testing "unknown severity -> {:error <message>}"
    (let [r (check/validate-filter-spec {:severity #{:loud}})]
      (is (some? r))
      (is (string? (:error r)))
      (is (re-find #"severity" (:error r)))
      (is (re-find #"loud" (:error r)))))

  (testing "unknown code is accepted silently (open enum)"
    (is (nil? (check/validate-filter-spec {:code #{:nope}})))))

(defn- spit-ticket! [path frontmatter-yaml body]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (str "---\n" frontmatter-yaml "---\n\n" body)))

(deftest scan-test
  (testing "scans live + archive, returns parsed tickets with :archived? flag and counts"
    (with-tmp tmp
      (let [tdir (str (fs/path tmp ".tickets"))]
        (spit-ticket! (fs/path tdir "kno-01a--alpha.md")
                      "id: kno-01a\ntitle: A\nstatus: open\n" "")
        (spit-ticket! (fs/path tdir "archive" "kno-01b--bravo.md")
                      "id: kno-01b\ntitle: B\nstatus: closed\n" "")
        (let [{:keys [tickets parse-errors scanned]}
              (check/scan tmp ".tickets" (droot tmp))]
          (is (= 2 (count tickets)))
          (is (= [] parse-errors))
          (is (= {:live 1 :archive 1 :docs 0} scanned))
          (let [by-id (into {} (map (juxt #(get-in % [:frontmatter :id]) identity)) tickets)]
            (is (false? (:archived? (get by-id "kno-01a"))))
            (is (true?  (:archived? (get by-id "kno-01b"))))
            (is (string? (:path (get by-id "kno-01a")))))))))

  (testing "tolerant per-file loader: malformed YAML becomes a parse-error, others still load"
    (with-tmp tmp
      (let [tdir (str (fs/path tmp ".tickets"))]
        (spit-ticket! (fs/path tdir "kno-01a--ok.md")
                      "id: kno-01a\ntitle: OK\nstatus: open\n" "")
        ;; broken file: malformed YAML inside fences
        (fs/create-dirs tdir)
        (spit (str (fs/path tdir "kno-01b--broken.md"))
              "---\nid: kno-01b\n  : badly: indented\n---\n\nbody\n")
        (let [{:keys [tickets parse-errors scanned]}
              (check/scan tmp ".tickets" (droot tmp))]
          (is (= 1 (count tickets)) "the good ticket is still loaded")
          (is (= 1 (count parse-errors)))
          (is (string? (:path (first parse-errors))))
          (is (= {:live 2 :archive 0 :docs 0} scanned)
              ":scanned counts files attempted by glob, including parse failures")))))

  (testing "missing tickets-dir is fine: empty result, both counts zero"
    (with-tmp tmp
      (let [{:keys [tickets parse-errors scanned]}
            (check/scan tmp ".tickets" (droot tmp))]
        (is (= [] tickets))
        (is (= [] parse-errors))
        (is (= {:live 0 :archive 0 :docs 0} scanned))))))

(deftest legacy-acceptance-section-warning-test
  (testing "ticket whose body has a `## Acceptance Criteria` section emits a :legacy_acceptance_section warning"
    (let [body    (str "## Description\n\nFoo bar.\n\n"
                       "## Acceptance Criteria\n\n"
                       "- [ ] Thing one\n"
                       "- [x] Thing two\n")
          tickets [(assoc (ticket "a" "open" [] :title "T") :body body)]
          issues  (issues-of (run-with tickets) :legacy_acceptance_section)]
      (is (= 1 (count issues)))
      (let [issue (first issues)]
        (is (= :warning (:severity issue))
            "legacy AC body section is a migration nudge, not data corruption")
        (is (= :legacy_acceptance_section (:code issue)))
        (is (= ["a"] (:ids issue)))
        (is (string? (:message issue)))
        (is (re-find #"migrate-ac" (:message issue))
            "message points users at `knot migrate-ac`"))))

  (testing "ticket without an Acceptance Criteria body section emits no warning"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Description\n\nNo AC section here.\n")]
          issues  (issues-of (run-with tickets) :legacy_acceptance_section)]
      (is (= 0 (count issues)))))

  (testing "structured :acceptance frontmatter alone (no body section) emits no warning"
    (let [tickets [(assoc (ticket "a" "open" []
                                  :title "T"
                                  :acceptance [{:title "x" :done false}])
                          :body "## Description\n\nplain body.\n")]
          issues  (issues-of (run-with tickets) :legacy_acceptance_section)]
      (is (= 0 (count issues))
          "the structured form is the post-migration end-state — no warning")))

  (testing "warning is filterable by --code legacy_acceptance_section"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Acceptance Criteria\n\n- [ ] thing\n")
                   (ticket "b" "wat" [] :title "T")]
          all     (:issues (run-with tickets))
          filtered (check/filter-issues all {:code #{:legacy_acceptance_section}})]
      (is (= 1 (count filtered)))
      (is (every? #(= :legacy_acceptance_section (:code %)) filtered))))

  (testing "warning is filterable by --severity warning"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Acceptance Criteria\n\n- [ ] thing\n")
                   (ticket "b" "wat" [] :title "T")]
          all      (:issues (run-with tickets))
          warnings (check/filter-issues all {:severity #{:warning}})]
      (is (every? #(= :warning (:severity %)) warnings))
      (is (some #(= :legacy_acceptance_section (:code %)) warnings)))))

(deftest reserved-section-warning-test
  (testing "each graph heading in a body emits a :reserved_section warning naming its field"
    (let [body    (str "## Description\n\nFoo.\n\n"
                       "## Blockers\n\n- b\n\n"
                       "## Blocking\n\n- c\n\n"
                       "## Children\n\n- d\n\n"
                       "## Linked\n\n- e\n")
          tickets [(assoc (ticket "a" "open" [] :title "T") :body body)]
          issues  (issues-of (run-with tickets) :reserved_section)]
      (is (= 4 (count issues)))
      (is (every? #(= :warning (:severity %)) issues)
          "a hand-written graph section is drift, not corruption")
      (is (every? #(= ["a"] (:ids %)) issues))
      (is (= #{"Blockers" "Blocking" "Children" "Linked"}
             (set (map #(second (re-find #"'## (.+?)'" (:message %))) issues))))
      (is (every? #(re-find #"(?i)delete" (:message %)) issues)
          "message says to delete the section — there is no automatic fix")))

  (testing "an archived ticket is warned about too"
    (let [tickets [(assoc (archived-ticket "a" "closed" [] :title "T")
                          :body "## Linked\n\n- e\n")]
          issues  (issues-of (run-with tickets) :reserved_section)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))))

  (testing "the (d/t) progress suffix show renders on Children does not hide it"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Children (1/2)\n\n- d\n")]
          issues  (issues-of (run-with tickets) :reserved_section)]
      (is (= 1 (count issues)))
      (is (re-find #"'## Children'" (:message (first issues)))
          "the message names the bare reserved heading")
      (is (re-find #"other tickets' parent" (:message (first issues))))))

  (testing "a heading that merely contains a reserved name is not reserved"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Children of the plan\n\n- d\n\n## Known Blockers\n\n- e\n")]
          issues  (issues-of (run-with tickets) :reserved_section)]
      (is (= 0 (count issues)))))

  (testing "a body with no reserved heading emits nothing"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Description\n\n### Blockers\n\nnot an H2.\n")]
          issues  (issues-of (run-with tickets) :reserved_section)]
      (is (= 0 (count issues)))))

  (testing "the near-synonyms are deliberately not detected"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Blocked by\n\n- b\n\n## Parent document\n\n- c\n")]
          issues  (issues-of (run-with tickets) :reserved_section)]
      (is (= 0 (count issues)))))

  (testing "a legacy Acceptance Criteria section keeps its own code, unchanged"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Acceptance Criteria\n\n- [ ] one\n")]
          all     (:issues (run-with tickets))]
      (is (= 0 (count (filterv #(= :reserved_section (:code %)) all)))
          "Acceptance Criteria stays :legacy_acceptance_section — it has a fix")
      (is (= 1 (count (filterv #(= :legacy_acceptance_section (:code %)) all))))))

  (testing "warning is filterable by --code reserved_section"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Children\n\n- d\n")
                   (ticket "b" "wat" [] :title "T")]
          all      (:issues (run-with tickets))
          filtered (check/filter-issues all {:code #{:reserved_section}})]
      (is (= 1 (count filtered)))
      (is (every? #(= :reserved_section (:code %)) filtered)))))

(deftest duplicate-section-warning-test
  (testing "a heading repeated in one body emits a :duplicate_section warning naming it"
    (let [body    (str "## Description\n\nFirst copy.\n\n"
                       "## Description\n\nSecond copy.\n")
          tickets [(assoc (ticket "a" "open" [] :title "T") :body body)]
          issues  (issues-of (run-with tickets) :duplicate_section)]
      (is (= 1 (count issues))
          "one issue per repeated heading, not one per copy")
      (is (= :warning (:severity (first issues))))
      (is (= ["a"] (:ids (first issues))))
      (is (re-find #"'## Description'" (:message (first issues)))
          "the message names the heading")
      (is (re-find #"--body|knot edit" (:message (first issues)))
          "the message names the manual repair — there is no automatic dedup")))

  (testing "each repeated heading gets its own issue; a heading appearing once does not"
    (let [body    (str "## Description\n\none\n\n## Design\n\nd\n\n"
                       "## Description\n\ntwo\n\n## Notes\n\nn\n\n"
                       "## Design\n\ne\n\n## Design\n\nf\n")
          tickets [(assoc (ticket "a" "open" [] :title "T") :body body)]
          issues  (issues-of (run-with tickets) :duplicate_section)]
      (is (= 2 (count issues)))
      (is (= #{"Description" "Design"}
             (set (map #(second (re-find #"'## (.+?)'" (:message %))) issues))))))

  (testing "headings that slugify alike are one duplicated section, named by the first spelling"
    (let [body    (str "## Description\n\nFirst copy.\n\n"
                       "## description\n\nSecond copy.\n")
          tickets [(assoc (ticket "a" "open" [] :title "T") :body body)]
          issues  (issues-of (run-with tickets) :duplicate_section)]
      (is (= 1 (count issues))
          "body-sections keys both under \"description\" and concatenates them")
      (is (re-find #"'## Description'" (:message (first issues))))))

  (testing "a heading repeated at ### or deeper is not a duplicate section"
    (let [body    "## Description\n\n### Notes\n\none\n\n### Notes\n\ntwo\n"
          tickets [(assoc (ticket "a" "open" [] :title "T") :body body)]
          issues  (issues-of (run-with tickets) :duplicate_section)]
      (is (= 0 (count issues))
          "only ## headings split a body, so only they can duplicate a section")))

  (testing "an archived ticket is warned about too"
    (let [tickets [(assoc (archived-ticket "a" "closed" [] :title "T")
                          :body "## Design\n\none\n\n## Design\n\ntwo\n")]
          issues  (issues-of (run-with tickets) :duplicate_section)]
      (is (= 1 (count issues)))
      (is (= ["a"] (:ids (first issues))))))

  (testing "warning is filterable by --code duplicate_section"
    (let [tickets [(assoc (ticket "a" "open" [] :title "T")
                          :body "## Design\n\none\n\n## Design\n\ntwo\n")
                   (ticket "b" "wat" [] :title "T")]
          all      (:issues (run-with tickets))
          filtered (check/filter-issues all {:code #{:duplicate_section}})]
      (is (= 1 (count filtered)))
      (is (every? #(= :duplicate_section (:code %)) filtered)))))

(defn- spit-frontmatter-ticket! [path id title status body]
  (fs/create-dirs (fs/parent path))
  (spit (str path)
        (str "---\n"
             "id: " id "\n"
             "title: " title "\n"
             "status: " status "\n"
             "---\n\n"
             body)))

(deftest legacy-acceptance-warning-disappears-after-migrate-ac-test
  (testing "after `knot migrate-ac` runs, no ticket still triggers the legacy warning"
    (with-tmp tmp
      (let [tdir   ".tickets"
            tpath  (fs/path tmp tdir "kno-01a--alpha.md")
            body   (str "## Description\n\nDoit.\n\n"
                        "## Acceptance Criteria\n\n"
                        "- [ ] one\n"
                        "- [x] two\n")]
        (spit-frontmatter-ticket! tpath "kno-01a" "Alpha" "open" body)
        (let [{:keys [tickets]} (check/scan tmp tdir (store/docs-root tmp tdir nil))
              issues-before     (->> (check/run {:tickets tickets
                                                 :config  default-config
                                                 :scanned {:live 1 :archive 0}})
                                     :issues
                                     (filterv #(= :legacy_acceptance_section (:code %))))]
          (is (= 1 (count issues-before))
              "warning surfaces while the body section is still present"))
        ;; Run the actual migrate-ac command against the temp project, then re-scan.
        (let [ctx {:project-root      tmp
                   :tickets-dir       tdir
                   :prefix            "kno"
                   :statuses          (:statuses default-config)
                   :terminal-statuses (:terminal-statuses default-config)
                   :active-status     (:active-status default-config)
                   :types             (:types default-config)
                   :modes             (:modes default-config)
                   :default-type      "task"
                   :default-mode      "hitl"
                   :default-priority  3
                   :now               "2026-05-06T00:00:00Z"}]
          (cli/migrate-ac-cmd ctx {}))
        (let [{:keys [tickets]} (check/scan tmp tdir (store/docs-root tmp tdir nil))
              issues-after      (->> (check/run {:tickets tickets
                                                 :config  default-config
                                                 :scanned {:live 1 :archive 0}})
                                     :issues
                                     (filterv #(= :legacy_acceptance_section (:code %))))
              loaded            (store/load-all tmp tdir)]
          (is (= 0 (count issues-after))
              "after migrate-ac the body section is gone, so the warning self-clears")
          (is (= 1 (count loaded)) "the migrated ticket round-trips on disk")
          (let [migrated-fm (:frontmatter (first loaded))]
            (is (= [{:title "one" :done false}
                    {:title "two" :done true}]
                   (:acceptance migrated-fm))
                "the body bullets were lifted into structured frontmatter")))))))

(defn- stamped-skill-md
  "SKILL.md text as `knot skill install` writes it, stamped with `v`."
  [v]
  (str "---\nname: knot\n---\n<!-- installed by knot " v " -->\n# knot\n"))

(deftest skill-stale-test
  (let [path   "/p/.claude/skills/knot/SKILL.md"
        stale  (fn [text & {:as extra}]
                 (issues-of (check/run (merge {:tickets []
                                               :skill   {:path path :text text}
                                               :version "0.12.0"}
                                              extra))
                            :skill_stale))]
    (testing "a stamp matching the CLI version -> no issue"
      (is (= [] (stale (stamped-skill-md "0.12.0")))))

    (testing "a pre-release CLI version still matches its own stamp"
      (is (= [] (issues-of (check/run {:tickets [] :version "0.13.0-rc1"
                                       :skill   {:path path :text (stamped-skill-md "0.13.0-rc1")}})
                           :skill_stale))))

    (testing "no project copy -> no issue"
      (is (= [] (issues-of (check/run {:tickets [] :version "0.12.0"}) :skill_stale))))

    (testing "an older stamp -> one global warning naming both versions"
      (let [[issue :as issues] (stale (stamped-skill-md "0.9.0"))]
        (is (= 1 (count issues)))
        (is (= :warning (:severity issue)))
        (is (= [] (:ids issue)))
        (is (= path (:path issue)))
        (is (= "0.9.0" (:value issue)))
        (is (re-find #"older" (:message issue))
            "versions compare numerically: 0.9.0 is older than 0.12.0")
        (is (str/includes? (:message issue) "0.12.0"))
        (is (str/ends-with? (:message issue) "run `knot skill install` and commit"))))

    (testing "a newer stamp -> warning that says newer"
      (let [[issue] (stale (stamped-skill-md "0.13.0"))]
        (is (= "0.13.0" (:value issue)))
        (is (re-find #"newer" (:message issue)))))

    (testing "a missing stamp -> warning with a null value"
      (let [[issue :as issues] (stale "---\nname: knot\n---\n# knot\n")]
        (is (= 1 (count issues)))
        (is (contains? issue :value))
        (is (nil? (:value issue)))
        (is (re-find #"missing or unreadable" (:message issue)))
        (is (str/ends-with? (:message issue) "run `knot skill install` and commit"))))

    (testing "an unparseable stamp or an unreadable file -> the missing warning"
      (doseq [text [(stamped-skill-md "banana") nil]]
        (let [[issue :as issues] (stale text)]
          (is (= 1 (count issues)))
          (is (nil? (:value issue)))
          (is (re-find #"missing or unreadable" (:message issue))))))

    (testing "positional ids do not skip the global check"
      (is (= 1 (count (stale (stamped-skill-md "0.9.0") :ids-filter #{"kno-01x"})))))))

(defn- doc-rec
  "Test helper: a document as `scan` hands it to `run` — parsed frontmatter
   plus the two annotations only the loader can supply. `owner-dir` is the
   directory the file was found in; `:ticket` is what the file itself claims.
   They are separate arguments because every placement check turns on the
   two disagreeing."
  [owner-dir id & {:as extras}]
  {:frontmatter (merge {:id id :ticket owner-dir :title "T" :type "spec"} extras)
   :body        (str "# " id "\n")
   :path        (str "/tmp/fake/docs/" owner-dir "/" id "--t.md")
   :owner-dir   owner-dir})

(defn- run-docs
  "Run with default-config, the given documents, and whichever tickets are
   needed for `:all-ids` to resolve."
  [docs & {:keys [tickets config] :or {tickets [] config default-config}}]
  (check/run {:tickets   tickets
              :documents docs
              :config    config
              :scanned   {:live (count tickets) :archive 0 :docs (count docs)}}))

(deftest doc-type-check-test
  (testing "a stored type outside the allow-list is reported under its own code"
    (let [issues (:issues (run-docs [(doc-rec "kno-01t" "kno-d01a" :type "wat")]
                                    :tickets [(ticket "kno-01t" "open" [] :title "T")]))]
      (is (= [:invalid_doc_type] (mapv :code issues)))
      (is (= :error (:severity (first issues))))
      (is (str/includes? (:message (first issues)) "\"wat\""))
      (is (str/includes? (:message (first issues)) "spec"))))

  (testing "every configured type is accepted"
    (doseq [t (:doc-types default-config)]
      (is (empty? (:issues (run-docs [(doc-rec "kno-01t" "kno-d01a" :type t)]
                                     :tickets [(ticket "kno-01t" "open" [] :title "T")])))
          (str t " must be accepted"))))

  (testing "an empty allow-list rejects every type instead of skipping the check"
    ;; `check-enum`'s `(seq allowed)` guard would skip validation entirely
    ;; here. That hole is exactly what :doc-types' default exists to close,
    ;; so the document arm must not inherit it.
    (is (= [:invalid_doc_type]
           (mapv :code (:issues (run-docs [(doc-rec "kno-01t" "kno-d01a")]
                                          :tickets [(ticket "kno-01t" "open" [] :title "T")]
                                          :config (assoc default-config :doc-types []))))))))

(deftest doc-precedence-test
  (testing "a misplaced document yields exactly one issue, the disagreement"
    (let [issues (:issues (run-docs [(doc-rec "kno-01dead" "kno-d01a" :ticket "kno-01live")]
                                    :tickets [(ticket "kno-01live" "open" [] :title "T")]))]
      (is (= 1 (count issues)))
      (is (= :doc_directory_mismatch (:code (first issues))))))

  (testing "same dead id in both places is an orphan, not a disagreement"
    (let [issues (:issues (run-docs [(doc-rec "kno-01dead" "kno-d01a")]))]
      (is (= [:doc_unknown_ticket] (mapv :code issues)))))

  (testing "two different dead ids is a disagreement, not suppressed by the orphan rule"
    (let [issues (:issues (run-docs [(doc-rec "kno-01deadA" "kno-d01a" :ticket "kno-01deadB")]))]
      (is (= [:doc_directory_mismatch] (mapv :code issues)))))

  (testing "a document with no ticket field is a disagreement, worded without a literal nil"
    (let [issues (:issues (run-docs [(doc-rec "kno-01t" "kno-d01a" :ticket nil)]
                                    :tickets [(ticket "kno-01t" "open" [] :title "T")]))]
      (is (= [:doc_directory_mismatch] (mapv :code issues)))
      (is (str/includes? (:message (first issues)) "has no ticket field"))
      (is (not (str/includes? (:message (first issues)) "nil")))))

  (testing "a document under an archived ticket is neither misplaced nor orphaned"
    ;; Documents do not follow their ticket into archive/; identity rides the
    ;; ticket field, not the path.
    (is (empty? (:issues (run-docs [(doc-rec "kno-01old" "kno-d01a")]
                                   :tickets [(archived-ticket "kno-01old" "closed" [] :title "T")]))))))

(deftest duplicate-doc-id-test
  (testing "two files claiming one document id are reported once, naming both paths"
    (let [a      (doc-rec "kno-01t" "kno-d01a")
          b      (assoc (doc-rec "kno-01t" "kno-d01a") :path "/tmp/fake/docs/kno-01t/kno-d01a--copy.md")
          issues (:issues (run-docs [a b] :tickets [(ticket "kno-01t" "open" [] :title "T")]))]
      (is (= [:duplicate_doc_id] (mapv :code issues)))
      (is (str/includes? (:message (first issues)) (:path a)))
      (is (str/includes? (:message (first issues)) (:path b)))))

  (testing "distinct ids are not duplicates"
    (is (empty? (:issues (run-docs [(doc-rec "kno-01t" "kno-d01a")
                                    (doc-rec "kno-01t" "kno-d01b")]
                                   :tickets [(ticket "kno-01t" "open" [] :title "T")]))))))

(deftest legacy-documents-heading-test
  (testing "a pre-existing heading warns and does not fail the check"
    (let [t      (assoc (ticket "kno-01t" "open" [] :title "T")
                        :body "## Documents\n\nold prose\n")
          issues (issues-of (run-with [t]) :legacy_documents_section)]
      (is (= 1 (count issues)))
      (is (= :warning (:severity (first issues))))
      (is (re-find #"remove" (:message (first issues))))))

  (testing "the heading is not ALSO reported as a generic reserved section"
    ;; It is reserved, so `check-reserved-sections` would otherwise fire too
    ;; — two warnings and two remedies for one heading.
    (let [t (assoc (ticket "kno-01t" "open" [] :title "T")
                   :body "## Documents\n\nold prose\n")]
      (is (empty? (issues-of (run-with [t]) :reserved_section)))))

  (testing "the warning self-clears once the heading is gone"
    (let [t (assoc (ticket "kno-01t" "open" [] :title "T") :body "## Notes\n\nfine\n")]
      (is (empty? (issues-of (run-with [t]) :legacy_documents_section))))))

(deftest scanned-counts-documents-test
  (testing "the scanned envelope reports the document count"
    (is (= {:live 1 :archive 0 :docs 2}
           (:scanned (run-docs [(doc-rec "kno-01t" "kno-d01a")
                                (doc-rec "kno-01t" "kno-d01b")]
                               :tickets [(ticket "kno-01t" "open" [] :title "T")])))))

  (testing "a project with no documents still reports the key"
    (is (= 0 (:docs (:scanned (run-with [])))))))

(deftest misplaced-document-in-ticket-dir-test
  ;; R24 / AC-16. A document file in the ticket directory is a MISPLACED
  ;; DOCUMENT, not a malformed ticket. Diagnosed as a ticket it draws
  ;; :invalid_type and :missing_required_field — two issues whose repair is
  ;; "add a status and a valid type", which is exactly wrong: the file is
  ;; well-formed and only in the wrong place.
  (testing "a document planted in the live ticket directory is diagnosed as misplaced"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-d01bbb--misplaced.md"))
            (str "---\nid: kno-d01bbb\nticket: kno-01aaa\ntitle: T\ntype: spec\n"
                 "created: 2026-01-01T00:00:00Z\nupdated: 2026-01-01T00:00:00Z\n---\n\nbody\n"))
      (spit (str (fs/path tmp ".tickets" "kno-01aaa--owner.md"))
            (str "---\nid: kno-01aaa\ntitle: Owner\nstatus: open\ntype: task\n"
                 "priority: 2\ncreated: 2026-01-01T00:00:00Z\nupdated: 2026-01-01T00:00:00Z\n---\n\n"))
      (let [scanned (check/scan tmp ".tickets" (droot tmp))
            issues  (:issues (check/run (assoc scanned :config default-config)))
            codes   (mapv :code issues)]
        (is (= 1 (count (:tickets scanned)))
            "the document must not be loaded into the ticket corpus")
        (is (= ["kno-d01bbb"] (mapv #(get-in % [:frontmatter :id]) (:documents scanned)))
            "it must be loaded into the document corpus instead")
        (is (= [:doc_directory_mismatch] codes)
            "one issue, naming the misplacement — never :invalid_type or :missing_required_field")
        (is (str/includes? (:message (first issues)) "kno-01aaa")
            "the message names the ticket the file claims, so the repair is obvious")
        (is (str/ends-with? (:path (first issues)) "kno-d01bbb--misplaced.md")))))

  (testing "a document planted in archive/ is diagnosed the same way"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "archive" "kno-d01bbb--misplaced.md"))
            (str "---\nid: kno-d01bbb\nticket: kno-01aaa\ntitle: T\ntype: spec\n"
                 "created: 2026-01-01T00:00:00Z\nupdated: 2026-01-01T00:00:00Z\n---\n\nbody\n"))
      (let [scanned (check/scan tmp ".tickets" (droot tmp))
            codes   (mapv :code (:issues (check/run (assoc scanned :config default-config))))]
        (is (empty? (:tickets scanned)))
        (is (= [:doc_directory_mismatch] codes)))))

  (testing "a real ticket file is still loaded as a ticket"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01aaa--owner.md"))
            (str "---\nid: kno-01aaa\ntitle: Owner\nstatus: open\ntype: task\n"
                 "priority: 2\ncreated: 2026-01-01T00:00:00Z\nupdated: 2026-01-01T00:00:00Z\n---\n\n"))
      (let [scanned (check/scan tmp ".tickets" (droot tmp))]
        (is (= 1 (count (:tickets scanned))))
        (is (empty? (:documents scanned)))
        (is (empty? (:issues (check/run (assoc scanned :config default-config)))))))))

(deftest unreachable-documents-test
  ;; A mistyped or newly-set :docs-dir points the corpus somewhere empty.
  ;; Every document surface then honestly reports nothing, and `check` used
  ;; to agree with them — so the tool said the project was healthy while the
  ;; documents sat where nothing would ever look again.
  (testing "documents at the default root are reported when :docs-dir points elsewhere"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "docs" "kno-01t"))
      (spit (str (fs/path tmp ".tickets" "docs" "kno-01t" "kno-d01a--p.md"))
            "---\nid: kno-d01a\nticket: kno-01t\ntitle: T\ntype: spec\n---\n\nbody\n")
      (let [scanned (check/scan tmp ".tickets" (str (fs/path tmp "elsewhere")))
            issues  (:issues (check/run (assoc scanned :config default-config)))
            issue   (first (filter #(= :unreachable_documents (:code %)) issues))]
        (is (some? issue) (str "expected the orphaned corpus to be reported, got "
                               (pr-str (mapv :code issues))))
        (is (= :warning (:severity issue))
            "nothing is corrupt and the repair is manual, so it warns")
        (is (str/includes? (:message issue) "1 document at ")
            "the count tells you how much is stranded, and agrees with its verb")
        (is (not (str/includes? (:message issue) "are outside"))
            "one document is, it does not are")
        (is (str/includes? (:message issue) ".tickets")
            "and the message names where they actually are"))))

  (testing "no report when the configured root is the one holding them"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp "elsewhere" "kno-01t"))
      (spit (str (fs/path tmp "elsewhere" "kno-01t" "kno-d01a--p.md"))
            "---\nid: kno-d01a\nticket: kno-01t\ntitle: T\ntype: spec\n---\n\nbody\n")
      (let [scanned (check/scan tmp ".tickets" (str (fs/path tmp "elsewhere")))
            codes   (mapv :code (:issues (check/run (assoc scanned :config default-config))))]
        (is (not (some #{:unreachable_documents} codes))))))

  (testing "no report on the default layout, however empty"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [scanned (check/scan tmp ".tickets" (store/docs-root tmp ".tickets" nil))
            codes   (mapv :code (:issues (check/run (assoc scanned :config default-config))))]
        (is (not (some #{:unreachable_documents} codes))))))

  (testing "no report when the configured root already holds documents"
    ;; Both populated is a deliberate migration in progress, not a mistype.
    (with-tmp tmp
      (doseq [root [".tickets/docs" "elsewhere"]]
        (fs/create-dirs (fs/path tmp root "kno-01t"))
        (spit (str (fs/path tmp root "kno-01t" "kno-d01a--p.md"))
              "---\nid: kno-d01a\nticket: kno-01t\ntitle: T\ntype: spec\n---\n\nbody\n"))
      (let [scanned (check/scan tmp ".tickets" (str (fs/path tmp "elsewhere")))
            codes   (mapv :code (:issues (check/run (assoc scanned :config default-config))))]
        (is (not (some #{:unreachable_documents} codes)))))))
