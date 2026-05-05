(ns knot.check-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [knot.check :as check]))

(defmacro ^:private with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(def ^:private default-config
  {:statuses          ["open" "in_progress" "closed"]
   :terminal-statuses #{"closed"}
   :active-status     "open"
   :types             ["task" "bug" "feature" "chore" "spike"]
   :modes             ["afk" "hitl"]})

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
      (is (= {:live 0 :archive 0} (:scanned result))))))

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
      (is (= {:live 2 :archive 0} (:scanned result)))))

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
              (check/scan tmp ".tickets")]
          (is (= 2 (count tickets)))
          (is (= [] parse-errors))
          (is (= {:live 1 :archive 1} scanned))
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
              (check/scan tmp ".tickets")]
          (is (= 1 (count tickets)) "the good ticket is still loaded")
          (is (= 1 (count parse-errors)))
          (is (string? (:path (first parse-errors))))
          (is (= {:live 2 :archive 0} scanned)
              ":scanned counts files attempted by glob, including parse failures")))))

  (testing "missing tickets-dir is fine: empty result, both counts zero"
    (with-tmp tmp
      (let [{:keys [tickets parse-errors scanned]}
            (check/scan tmp ".tickets")]
        (is (= [] tickets))
        (is (= [] parse-errors))
        (is (= {:live 0 :archive 0} scanned))))))
