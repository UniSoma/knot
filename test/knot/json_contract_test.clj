(ns knot.json-contract-test
  "JSON envelope contract tests for `--json` commands. Pins the runtime
   shape of every `--json` command so drift is caught at `bb test` time.

   The unit-level shape coverage in `output_test` exercises the
   render functions in isolation; this namespace exercises the wired
   CLI by spawning a subprocess so the assertions cover argument
   parsing, handler routing, error projection, and envelope wrapping
   together — i.e. exactly the shape an external consumer would see.

   Tests are organized by AC bullet from kno-01kqwgeba7jz:

     1. Envelope invariants — schema_version=1, ok present, data XOR
        error (with the documented `knot check` exception).
     2. Per-command `data` shape (one deftest per --json command).
     3. Vector-default contract — tags/deps/links/external_refs always
        arrays in ticket payloads (read + mutating envelopes).
     4. meta.archived_to on close --json and terminal status --json.
     5. Error envelopes — not_found, ambiguous_id, cycle, check
        cannot-scan."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private project-root
  (or (System/getProperty "user.dir") "."))

(defmacro ^:private with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(defn- run-knot
  "Run `knot <args...>` with `cwd` as the working directory. Returns
   `{:exit n :out s :err s}`. Stdin is closed so commands probing stdin
   do not block."
  [cwd & args]
  @(p/process (concat ["bb" "-cp" (str (fs/path project-root "src"))
                       "-e"
                       (str "(require '[knot.main]) "
                            "(apply (resolve 'knot.main/-main) *command-line-args*)")
                       "--"]
                      args)
              {:dir cwd :in "" :out :string :err :string}))

(defn- parse-envelope
  "Parse a `--json` stdout string into a Clojure map with keyword keys."
  [out]
  (json/parse-string (str/trim out) true))

(defn- assert-envelope-invariants!
  "Pin the v0.3 envelope invariants in one place:
     1. `:schema_version` is `1`.
     2. `:ok` is present and is a boolean.
     3. `:data` and `:error` are mutually exclusive.
        - On `:ok true`, `:data` must be present and `:error` absent.
        - On `:ok false`, `:error` must be present and `:data` absent
          UNLESS `:check?` is passed — `knot check`'s exit-1 path
          deliberately emits `{ok:false, data:{...}}` because `:ok`
          mirrors a health verdict.
   `label` is included in failure messages so a top-level loop over
   commands tells you which one drifted."
  ([envelope label] (assert-envelope-invariants! envelope label {}))
  ([envelope label {:keys [check?]}]
   (is (= 1 (:schema_version envelope))
       (str label ": schema_version must be 1"))
   (is (contains? envelope :ok)
       (str label ": :ok key must be present"))
   (is (boolean? (:ok envelope))
       (str label ": :ok must be a boolean"))
   (cond
     (true? (:ok envelope))
     (do
       (is (contains? envelope :data)
           (str label ": ok:true envelope must carry :data"))
       (is (not (contains? envelope :error))
           (str label ": ok:true envelope must not carry :error")))

     check?
     (is (or (contains? envelope :data)
             (contains? envelope :error))
         (str label ": check ok:false envelope must carry :data or :error"))

     :else
     (do
       (is (contains? envelope :error)
           (str label ": ok:false envelope must carry :error"))
       (is (not (contains? envelope :data))
           (str label ": non-check ok:false envelope must not carry :data"))))))

(defn- id-of
  "Extract the id from a `knot create` stdout path. The path is of the
   form `.tickets/<id>--<slug>.md`; `slug` is needed so the regex anchors
   correctly and the id capture is unambiguous."
  [create-out slug]
  (->> (str (fs/file-name (str/trim create-out)))
       (re-matches (re-pattern (str "(.+)--" slug "\\.md")))
       second))

(defn- seed-read-fixture!
  "Seed `tmp` with a representative project for read-command coverage:
     * `bare`     — minimal ticket (no tags/deps/links/external_refs)
     * `tagged`   — fully-populated ticket (tags, parent, external-ref)
     * `closed-x` — closed ticket so `closed --json` has a row
     * `blocker` / `blocked` — paired so `blocked --json` and dep tree
       have non-trivial shape

   Returns `{:bare-id :tagged-id :closed-id :blocker-id :blocked-id}`."
  [tmp]
  (let [{bare-out :out} (run-knot tmp "create" "bare")
        bare-id   (id-of bare-out "bare")
        {tagged-out :out} (run-knot tmp "create" "tagged"
                                    "--tags" "p0,auth"
                                    "--external-ref" "JIRA-1")
        tagged-id (id-of tagged-out "tagged")
        {closed-out :out} (run-knot tmp "create" "closed-x")
        closed-id (id-of closed-out "closed-x")
        _         (run-knot tmp "close" closed-id "--summary" "shipped")
        {blocker-out :out} (run-knot tmp "create" "blocker")
        blocker-id (id-of blocker-out "blocker")
        {blocked-out :out} (run-knot tmp "create" "blocked")
        blocked-id (id-of blocked-out "blocked")
        _          (run-knot tmp "dep" blocked-id blocker-id)]
    {:bare-id    bare-id
     :tagged-id  tagged-id
     :closed-id  closed-id
     :blocker-id blocker-id
     :blocked-id blocked-id}))

(def ^:private ticket-vector-default-keys
  "Frontmatter keys that ticket payloads always carry as arrays in any
   `--json` envelope, even when absent on disk. Sourced from
   `output/json-vector-default-keys`; mirrored here as a literal so
   contract drift is visible in the test (re-exporting via require would
   make the assertion tautological)."
  [:tags :deps :links :external_refs])

(defn- assert-ticket-vector-defaults!
  "Assert that every key in `ticket-vector-default-keys` is present and
   is a vector on the given ticket map. `label` is included in failure
   messages so a top-level loop tells you which command drifted."
  [ticket label]
  (doseq [k ticket-vector-default-keys]
    (is (contains? ticket k)
        (str label ": ticket payload must contain " k))
    (is (vector? (get ticket k))
        (str label ": " k " must be a vector, got " (pr-str (get ticket k))))))

(deftest vector-default-contract-read-commands-test
  ;; Pin AC#3: tags/deps/links/external_refs always appear as arrays in
  ;; ticket payloads emitted by read --json commands, regardless of
  ;; whether the on-disk YAML carries the key. The fixture seeds a
  ;; tag-less, dep-less, link-less, ref-less ticket so the *absent*
  ;; case (defaults injected at the JSON boundary) is exercised — the
  ;; *present* case (round-trip) is already pinned in the existing
  ;; jsonify-vector-defaults tests in output_test.
  (with-tmp tmp
    (let [{:keys [bare-id blocker-id]} (seed-read-fixture! tmp)]

      (testing "list --json — every ticket carries the four vector defaults"
        (let [{:keys [out]} (run-knot tmp "list" "--json")
              envelope (parse-envelope out)
              tickets  (:data envelope)]
          (is (seq tickets) "fixture must produce at least one live ticket")
          (doseq [t tickets]
            (assert-ticket-vector-defaults!
             t (str "list --json ticket " (:id t))))))

      (testing "show --json — bare ticket carries the four vector defaults"
        (let [{:keys [out]} (run-knot tmp "show" bare-id "--json")
              envelope (parse-envelope out)]
          (assert-ticket-vector-defaults!
           (:data envelope) "show --json")))

      (testing "ready --json — every ticket carries the four vector defaults"
        (let [{:keys [out]} (run-knot tmp "ready" "--json")
              envelope (parse-envelope out)]
          (is (seq (:data envelope)) "fixture must have at least one ready ticket")
          (doseq [t (:data envelope)]
            (assert-ticket-vector-defaults!
             t (str "ready --json ticket " (:id t))))))

      (testing "blocked --json — every ticket carries the four vector defaults"
        (let [{:keys [out]} (run-knot tmp "blocked" "--json")
              envelope (parse-envelope out)]
          (is (seq (:data envelope))
              "fixture seeds a blocked ticket; envelope must list it")
          (doseq [t (:data envelope)]
            (assert-ticket-vector-defaults!
             t (str "blocked --json ticket " (:id t))))))

      (testing "closed --json — every ticket carries the four vector defaults"
        (let [{:keys [out]} (run-knot tmp "closed" "--json")
              envelope (parse-envelope out)]
          (is (seq (:data envelope))
              "fixture closes one ticket; closed envelope must list it")
          (doseq [t (:data envelope)]
            (assert-ticket-vector-defaults!
             t (str "closed --json ticket " (:id t))))))

      (testing "dep tree --json root — does not carry the ticket vector defaults"
        ;; Sanity: the contract is for *ticket* payloads (frontmatter
        ;; passthrough). dep tree's payload is `{id, title, status,
        ;; deps}` — `deps` is the *children* slot, not the ticket's
        ;; deps frontmatter list, so the vector defaults explicitly do
        ;; NOT apply here. Pinning this asymmetry so future
        ;; generalizations of the contract do not silently include dep
        ;; tree (which would conflate two distinct meanings of `deps`).
        (let [{:keys [out]} (run-knot tmp "dep" "tree" blocker-id "--json")
              envelope (parse-envelope out)]
          (is (vector? (get-in envelope [:data :deps]))
              "dep tree's :deps (children) slot is always a vector")
          (is (not (contains? (:data envelope) :tags))
              "dep tree root is a tree node, not a ticket payload"))))))

(deftest vector-default-contract-mutating-envelopes-test
  ;; Pin AC#3 for mutating envelopes: every command routed through
  ;; `output/touched-ticket-json` / `touched-tickets-json` always
  ;; emits the four ticket vector fields as arrays. Each mutator runs
  ;; on a fresh ticket *with no on-disk values for any of the four
  ;; keys* so the absent-case default-injection is exercised, not the
  ;; round-trip case.
  (testing "create --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "create --json"))))

  (testing "start --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "start" id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "start --json"))))

  (testing "status --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "status" id "in_progress" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "status --json"))))

  (testing "close --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "close" id "--summary" "x" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "close --json"))))

  (testing "reopen --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            _  (run-knot tmp "close" id "--summary" "x")
            {:keys [out]} (run-knot tmp "reopen" id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "reopen --json"))))

  (testing "dep --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            {:keys [out]} (run-knot tmp "dep" a-id b-id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "dep --json"))))

  (testing "undep --json — bare ticket carries vector defaults (deps drained to [])"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "dep" a-id b-id)
            {:keys [out]} (run-knot tmp "undep" a-id b-id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "undep --json")
        (is (= [] (get-in envelope [:data :deps]))
            "removing the last dep drains :deps to [] (vector default kicks in)"))))

  (testing "add-note --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "add-note" id "noted" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "add-note --json"))))

  (testing "update --json — bare ticket carries vector defaults"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "update" id "--priority" "1" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-vector-defaults! (:data envelope) "update --json"))))

  (testing "link --json — every ticket in the array carries vector defaults"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            {:keys [out]} (run-knot tmp "link" a-id b-id "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)) "link --json data is an array")
        (is (= 2 (count (:data envelope))))
        (doseq [t (:data envelope)]
          (assert-ticket-vector-defaults!
           t (str "link --json ticket " (:id t)))))))

  (testing "unlink --json — every ticket in the array carries vector defaults"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "link" a-id b-id)
            {:keys [out]} (run-knot tmp "unlink" a-id b-id "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)))
        (doseq [t (:data envelope)]
          (assert-ticket-vector-defaults!
           t (str "unlink --json ticket " (:id t))))))))

(deftest meta-archived-to-contract-test
  ;; Pin AC#4: close --json and any status --json transition to a
  ;; terminal status emit a top-level :meta slot carrying the new
  ;; archive path. Non-terminal transitions (start, status to a
  ;; non-terminal status, update, dep, link, etc.) MUST NOT emit
  ;; :meta — pinned in the negative cases below.
  (testing "close --json carries :meta with :archived_to"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "close" id "--summary" "x" "--json")
            envelope (parse-envelope out)
            archived-to (get-in envelope [:meta :archived_to])]
        (is (contains? envelope :meta)
            "close --json envelope must contain :meta")
        (is (string? archived-to)
            "meta.archived_to must be a string path")
        (is (str/includes? archived-to ".tickets/archive/")
            (str "archived path must point under .tickets/archive/, got "
                 (pr-str archived-to)))
        (is (fs/exists? archived-to)
            "the archive path must point to an extant file"))))

  (testing "status --json to a terminal status carries :meta with :archived_to"
    ;; The default project's only terminal status is `closed`, so this
    ;; confirms that `status <id> closed` (the generic transition)
    ;; routes through the same archive + meta-emission code path as
    ;; `close`. Pinning here so a future refactor that decoupled
    ;; close/archive from generic status transitions wouldn't silently
    ;; drop :meta on the latter.
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "status" id "closed" "--json")
            envelope (parse-envelope out)
            archived-to (get-in envelope [:meta :archived_to])]
        (is (string? archived-to))
        (is (str/includes? archived-to ".tickets/archive/")))))

  (testing "status --json to a non-terminal status DOES NOT carry :meta"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "status" id "in_progress" "--json")
            envelope (parse-envelope out)]
        (is (not (contains? envelope :meta))
            "in_progress is not a terminal status — no :meta slot"))))

  (testing "start --json DOES NOT carry :meta"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "start" id "--json")
            envelope (parse-envelope out)]
        (is (not (contains? envelope :meta))
            "start is non-terminal — no :meta slot"))))

  (testing "update --json DOES NOT carry :meta"
    ;; Sentinel: update never archives, so :meta absence is part of
    ;; the contract — already pinned in update-end-to-end-test, mirrored
    ;; here so the meta contract surface is centrally readable.
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "update" id "--priority" "1" "--json")
            envelope (parse-envelope out)]
        (is (not (contains? envelope :meta)))))))

(defn- assert-not-found-envelope!
  "Pin the v0.3 not_found error envelope shape for an id-resolving
   --json command. `exit` must be 1; stderr blank (envelope routes to
   stdout); envelope satisfies the central invariants with `ok:false`,
   carries `error.code = \"not_found\"`, and the error message
   includes the missing id (so consumers can surface it without
   re-fetching context)."
  [{:keys [exit out err]} missing-id label]
  (let [envelope (parse-envelope out)]
    (is (= 1 exit) (str label ": expected exit 1, got " exit "; err=" err))
    (is (str/blank? err)
        (str label ": json error envelope routes to stdout, not stderr"))
    (assert-envelope-invariants! envelope label)
    (is (= false (:ok envelope)))
    (is (= "not_found" (get-in envelope [:error :code]))
        (str label ": error.code must be \"not_found\""))
    (is (string? (get-in envelope [:error :message])))
    (is (str/includes? (get-in envelope [:error :message]) missing-id)
        (str label ": error.message must reference the missing id"))))

(deftest error-envelope-not-found-contract-test
  ;; Pin AC#5 (not_found): every id-resolving --json command emits the
  ;; same canonical not_found envelope shape on a missing id. One
  ;; central pin so a new id-resolving command added later forces a
  ;; one-line addition here, not a scattered deftest hunt.
  (let [missing "kno-ghost"]
    (testing "show --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "show" missing "--json") missing "show --json")))

    (testing "dep tree --json — not_found is NOT emitted (tolerant root)"
      ;; Dep tree is the documented exception: an unknown root resolves
      ;; to `{ok:true, data:{id, missing:true}}`. Pin the asymmetry
      ;; here too so adding `not_found` to dep tree later is a
      ;; deliberate change, not a drift. Asymmetry is also pinned in
      ;; the existing read-cmd-error-envelope-test (integration_test).
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (let [{:keys [exit out]} (run-knot tmp "dep" "tree" missing "--json")
              envelope (parse-envelope out)]
          (is (zero? exit))
          (is (true? (:ok envelope)))
          (is (true? (get-in envelope [:data :missing]))))))

    (testing "start --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "start" missing "--json") missing "start --json")))

    (testing "status --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "status" missing "open" "--json") missing "status --json")))

    (testing "close --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "close" missing "--summary" "x" "--json")
         missing "close --json")))

    (testing "reopen --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "reopen" missing "--json") missing "reopen --json")))

    (testing "dep --json — not_found on the `from` id"
      ;; `from` is strict-resolved; `to` is intentionally soft-resolved
      ;; (broken refs land verbatim — surfaced as `[missing]` in dep
      ;; tree, owned by the parent for `knot check`). Pin both
      ;; behaviors so refactors can't silently flip either contract.
      (with-tmp tmp
        (let [{:keys [out]} (run-knot tmp "create" "Real")
              real-id (id-of out "real")]
          (assert-not-found-envelope!
           (run-knot tmp "dep" missing real-id "--json") missing "dep --json (from)")
          (let [{:keys [exit out]}
                (run-knot tmp "dep" real-id missing "--json")
                envelope (parse-envelope out)]
            (is (zero? exit) "dep --json with a missing :to is tolerated (soft-resolution)")
            (is (true? (:ok envelope)))
            (is (= [missing] (get-in envelope [:data :deps]))
                "the missing id is recorded verbatim in :deps")))))

    (testing "undep --json — not_found on the `from` id"
      ;; Same asymmetry as dep: `from` strict, `to` soft. Soft-resolution
      ;; on `to` is what lets agents undo a previously broken ref by
      ;; typing it verbatim — tested in undep-cmd-json-test already.
      (with-tmp tmp
        (let [{:keys [out]} (run-knot tmp "create" "Real")
              real-id (id-of out "real")]
          (assert-not-found-envelope!
           (run-knot tmp "undep" missing real-id "--json") missing "undep --json (from)")
          (let [{:keys [exit]} (run-knot tmp "undep" real-id missing "--json")]
            (is (zero? exit) "undep --json with a missing :to is tolerated")))))

    (testing "link --json — not_found on EITHER id"
      ;; Unlike dep/undep, `link` resolves every id strictly — broken
      ;; symmetric peers would corrupt the graph because both sides are
      ;; written.
      (with-tmp tmp
        (let [{:keys [out]} (run-knot tmp "create" "Real")
              real-id (id-of out "real")]
          (assert-not-found-envelope!
           (run-knot tmp "link" real-id missing "--json") missing "link --json (b)")
          (assert-not-found-envelope!
           (run-knot tmp "link" missing real-id "--json") missing "link --json (a)"))))

    (testing "unlink --json — not_found on the `from` id"
      ;; Same asymmetry: `from` strict, `to` soft (lets a stale link be
      ;; undone by typing it verbatim).
      (with-tmp tmp
        (let [{:keys [out]} (run-knot tmp "create" "Real")
              real-id (id-of out "real")]
          (assert-not-found-envelope!
           (run-knot tmp "unlink" missing real-id "--json") missing "unlink --json (from)")
          (let [{:keys [exit]} (run-knot tmp "unlink" real-id missing "--json")]
            (is (zero? exit) "unlink --json with a missing :to is tolerated")))))

    (testing "add-note --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "add-note" missing "x" "--json") missing "add-note --json")))

    (testing "update --json — not_found"
      (with-tmp tmp
        (fs/create-dirs (fs/path tmp ".tickets"))
        (assert-not-found-envelope!
         (run-knot tmp "update" missing "--title" "X" "--json")
         missing "update --json")))))

(defn- seed-ambiguous-fixture!
  "Seed `tmp` with two tickets whose ids share a prefix that can be
   used to drive an ambiguous_id error. Returns the shared partial id
   that resolves to both."
  [tmp]
  (fs/create-dirs (fs/path tmp ".tickets"))
  (spit (str (fs/path tmp ".tickets" "kno-01abc111111--a.md"))
        "---\nid: kno-01abc111111\ntitle: A\nstatus: open\ntype: task\npriority: 2\nmode: hitl\n---\n\n# A\n")
  (spit (str (fs/path tmp ".tickets" "kno-01abc222222--b.md"))
        "---\nid: kno-01abc222222\ntitle: B\nstatus: open\ntype: task\npriority: 2\nmode: hitl\n---\n\n# B\n")
  "kno-01abc")

(defn- assert-ambiguous-envelope!
  "Pin the v0.3 ambiguous_id error envelope shape: exit 1, stdout
   carries the envelope, ok:false, error.code = \"ambiguous_id\",
   error.candidates is a vector of full ids in canonical sort order."
  [{:keys [exit out err]} expected-candidates label]
  (let [envelope (parse-envelope out)]
    (is (= 1 exit) (str label ": expected exit 1, got " exit "; err=" err))
    (is (str/blank? err))
    (assert-envelope-invariants! envelope label)
    (is (= false (:ok envelope)))
    (is (= "ambiguous_id" (get-in envelope [:error :code]))
        (str label ": error.code must be \"ambiguous_id\""))
    (let [candidates (get-in envelope [:error :candidates])]
      (is (vector? candidates)
          (str label ": error.candidates must be a vector"))
      (is (= expected-candidates candidates)
          (str label ": error.candidates must list the resolved full ids")))))

(deftest error-envelope-ambiguous-id-contract-test
  ;; Pin AC#5 (ambiguous_id): every strict-resolving --json command
  ;; emits the same canonical ambiguous_id envelope on a partial id
  ;; that resolves to >1 ticket. Soft-resolution sites (dep `:to`,
  ;; undep `:to`, unlink `:to`) are NOT in scope — those silently
  ;; persist the literal partial id; pinning that asymmetry is the
  ;; job of slice-8.
  (let [candidates ["kno-01abc111111" "kno-01abc222222"]]
    (testing "show --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "show" partial "--json") candidates "show --json"))))

    (testing "dep tree --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "dep" "tree" partial "--json") candidates "dep tree --json"))))

    (testing "start --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "start" partial "--json") candidates "start --json"))))

    (testing "status --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "status" partial "in_progress" "--json") candidates "status --json"))))

    (testing "close --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "close" partial "--summary" "x" "--json")
           candidates "close --json"))))

    (testing "reopen --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "reopen" partial "--json") candidates "reopen --json"))))

    (testing "dep --json — ambiguous_id on the `from` id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "dep" partial "kno-01abc111111" "--json")
           candidates "dep --json (from)"))))

    (testing "undep --json — ambiguous_id on the `from` id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "undep" partial "kno-01abc111111" "--json")
           candidates "undep --json (from)"))))

    (testing "link --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "link" "kno-01abc111111" partial "--json")
           candidates "link --json"))))

    (testing "unlink --json — ambiguous_id on the `from` id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "unlink" partial "kno-01abc111111" "--json")
           candidates "unlink --json"))))

    (testing "add-note --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "add-note" partial "x" "--json")
           candidates "add-note --json"))))

    (testing "update --json — ambiguous_id"
      (with-tmp tmp
        (let [partial (seed-ambiguous-fixture! tmp)]
          (assert-ambiguous-envelope!
           (run-knot tmp "update" partial "--title" "X" "--json")
           candidates "update --json"))))))

(deftest error-envelope-cycle-contract-test
  ;; Pin AC#5 (cycle): `dep --json` rejects cycle-creating edges with
  ;; `error.code = "cycle"` and a `error.cycle` vector path. The
  ;; vector form (rather than a free-text message) is what lets JSON
  ;; consumers display the offending path without re-parsing strings.
  (testing "dep --json on a cycle-creating edge emits the cycle envelope"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "dep" a-id b-id)
            {:keys [exit out err]} (run-knot tmp "dep" b-id a-id "--json")
            envelope (parse-envelope out)]
        (is (= 1 exit) (str "dep cycle err=" err))
        (is (str/blank? err) "cycle envelope routes to stdout, not stderr")
        (assert-envelope-invariants! envelope "dep --json (cycle)")
        (is (= false (:ok envelope)))
        (is (= "cycle" (get-in envelope [:error :code]))
            "error.code must be \"cycle\"")
        (let [path (get-in envelope [:error :cycle])]
          (is (vector? path)
              "error.cycle must be a vector path")
          (is (every? string? path))
          (is (some #{a-id} path)
              "the offending cycle path must include the from id")
          (is (some #{b-id} path)
              "the offending cycle path must include the to id"))))))

(deftest error-envelope-acceptance-incomplete-contract-test
  ;; Pin the v0.3 acceptance gate: when `close --json` (or terminal
  ;; `status --json` / `update --status <terminal> --json`) fires the
  ;; gate, stdout carries `{ok:false, error:{code:"acceptance_incomplete",
  ;; message, open_acceptance:[{title}, ...]}}` and the binary exits 1.
  ;; Stdout-only — JSON consumers should never have to read stderr.
  (testing "close --json on incomplete AC emits the acceptance_incomplete envelope"
    (with-tmp tmp
      (let [{create-out :out}      (run-knot tmp "create" "T"
                                             "--acceptance" "first AC"
                                             "--acceptance" "second AC")
            id                     (id-of create-out "t")
            _                      (run-knot tmp "start" id)
            {:keys [exit out err]} (run-knot tmp "close" id "--json")
            envelope               (parse-envelope out)]
        (is (= 1 exit) (str "gate-firing close should exit 1, err=" err))
        (is (str/blank? err)
            "acceptance_incomplete envelope routes to stdout, not stderr")
        (assert-envelope-invariants! envelope "close --json (acceptance_incomplete)")
        (is (= false (:ok envelope)))
        (is (= "acceptance_incomplete" (get-in envelope [:error :code])))
        (is (string? (get-in envelope [:error :message])))
        (let [open (get-in envelope [:error :open_acceptance])]
          (is (vector? open) "error.open_acceptance must be a vector")
          (is (= 2 (count open)))
          (is (= "first AC"  (get-in open [0 :title])))
          (is (= "second AC" (get-in open [1 :title])))))))

  (testing "update --status <terminal> --json on incomplete AC emits the same envelope"
    (with-tmp tmp
      (let [{create-out :out}      (run-knot tmp "create" "T"
                                             "--acceptance" "only AC")
            id                     (id-of create-out "t")
            _                      (run-knot tmp "start" id)
            {:keys [exit out]}     (run-knot tmp "update" id
                                             "--status" "closed" "--json")
            envelope               (parse-envelope out)]
        (is (= 1 exit))
        (assert-envelope-invariants! envelope "update --json (acceptance_incomplete)")
        (is (= "acceptance_incomplete" (get-in envelope [:error :code])))
        (is (= [{:title "only AC"}]
               (get-in envelope [:error :open_acceptance])))))))

(deftest error-envelope-open-children-contract-test
  ;; Pin the open-children gate envelope shape: when `close --json` (or
  ;; terminal `status --json` / `update --status <terminal> --json`)
  ;; fires the gate, stdout carries
  ;; `{ok:false, error:{code:"open_children", message, open_children:[<id>, ...]}}`
  ;; and the binary exits 1. Stdout-only — JSON consumers should never
  ;; have to read stderr.
  (testing "close --json on a parent with a non-terminal child emits the open_children envelope"
    (with-tmp tmp
      (let [{p-out :out}           (run-knot tmp "create" "Parent")
            pid                    (id-of p-out "parent")
            {c-out :out}           (run-knot tmp "create" "Child" "--parent" pid)
            cid                    (id-of c-out "child")
            _                      (run-knot tmp "start" pid "--force")
            {:keys [exit out err]} (run-knot tmp "close" pid "--json")
            envelope               (parse-envelope out)]
        (is (= 1 exit) (str "gate-firing close should exit 1, err=" err))
        (is (str/blank? err)
            "open_children envelope routes to stdout, not stderr")
        (assert-envelope-invariants! envelope "close --json (open_children)")
        (is (= false (:ok envelope)))
        (is (= "open_children" (get-in envelope [:error :code])))
        (is (string? (get-in envelope [:error :message])))
        (is (= [cid] (get-in envelope [:error :open_children]))))))

  (testing "start --json on a parent with a non-terminal child emits the open_children envelope"
    (with-tmp tmp
      (let [{p-out :out}           (run-knot tmp "create" "Parent")
            pid                    (id-of p-out "parent")
            {c-out :out}           (run-knot tmp "create" "Child" "--parent" pid)
            cid                    (id-of c-out "child")
            {:keys [exit out err]} (run-knot tmp "start" pid "--json")
            envelope               (parse-envelope out)]
        (is (= 1 exit) (str "gate-firing start should exit 1, err=" err))
        (is (str/blank? err)
            "open_children envelope routes to stdout, not stderr")
        (assert-envelope-invariants! envelope "start --json (open_children)")
        (is (= false (:ok envelope)))
        (is (= "open_children" (get-in envelope [:error :code])))
        (is (string? (get-in envelope [:error :message])))
        (is (= [cid] (get-in envelope [:error :open_children])))))))

(deftest error-envelope-check-cannot-scan-test
  ;; Pin AC#5 (check exit-2): `knot check --json` outside a project
  ;; emits an error envelope on stdout (NOT stderr — distinct from
  ;; the non-json mode) with no `:data` slot, and exits 2.
  (testing "check --json outside a project emits an exit-2 error envelope"
    (with-tmp tmp
      ;; tmp has no .knot.edn and no .tickets/ — nothing to scan.
      (let [{:keys [exit out err]} (run-knot tmp "check" "--json")
            envelope (parse-envelope out)]
        (is (= 2 exit)
            "exit 2 is reserved for unable-to-scan, distinct from exit 1 (errors found)")
        (is (str/blank? err)
            "json envelope routes to stdout, not stderr")
        (assert-envelope-invariants! envelope "check --json (cannot-scan)")
        (is (= false (:ok envelope)))
        (is (some? (get-in envelope [:error :code]))
            "error.code must be present")
        (is (string? (get-in envelope [:error :message])))
        (is (not (contains? envelope :data))
            "the cannot-scan envelope is one of the few ok:false-no-data shapes")))))

(def ^:private ticket-required-keys
  "Frontmatter keys every ticket carries on disk (and therefore in
   `--json` output) regardless of project state. Pinned here so a
   contract drift on one of these surfaces shows up in the test
   namespace, not just at runtime."
  [:id :title :status :type :priority :mode :created :updated])

(defn- assert-ticket-payload-shape!
  "Assert the canonical ticket shape: every required key is present
   and matches its expected type, plus the four vector defaults are
   arrays. `body?` is true for show/touched-ticket-style envelopes,
   false for ls-style (list/ready/blocked/closed/link/unlink)."
  [ticket label {:keys [body?]}]
  (doseq [k ticket-required-keys]
    (is (contains? ticket k)
        (str label ": ticket payload missing required key " k)))
  (is (string? (:id ticket))       (str label ": :id must be a string"))
  (is (string? (:title ticket))    (str label ": :title must be a string"))
  (is (string? (:status ticket))   (str label ": :status must be a string"))
  (is (string? (:type ticket))     (str label ": :type must be a string"))
  (is (integer? (:priority ticket)) (str label ": :priority must be an integer"))
  (is (string? (:mode ticket))     (str label ": :mode must be a string"))
  (is (string? (:created ticket))  (str label ": :created must be a string"))
  (is (string? (:updated ticket))  (str label ": :updated must be a string"))
  (assert-ticket-vector-defaults! ticket label)
  (if body?
    (is (string? (:body ticket))
        (str label ": body-included envelopes must carry :body as a string"))
    (is (not (contains? ticket :body))
        (str label ": ls-shape envelopes must omit :body"))))

(deftest data-shape-list-commands-test
  ;; Pin AC#2: the ls-shape envelope (list, ready, blocked, closed)
  ;; emits a vector of ticket objects, each with the canonical key
  ;; set, snake_case keys, and no :body.
  (with-tmp tmp
    (seed-read-fixture! tmp)
    (testing "list --json — array of ls-shape ticket objects"
      (let [{:keys [out]} (run-knot tmp "list" "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)) "list :data is an array")
        (is (every? map? (:data envelope)))
        (doseq [t (:data envelope)]
          (assert-ticket-payload-shape!
           t (str "list --json " (:id t)) {:body? false})
          (is (integer? (:leverage t))
              "list rows carry a leverage integer")
          (is (integer? (:coupling t))
              "list rows carry a coupling integer"))))

    (testing "ready --json — array of ls-shape ticket objects"
      (let [{:keys [out]} (run-knot tmp "ready" "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)))
        (is (seq (:data envelope)))
        (doseq [t (:data envelope)]
          (assert-ticket-payload-shape!
           t (str "ready --json " (:id t)) {:body? false})
          (is (integer? (:leverage t))
              "ready rows carry a leverage integer")
          (is (integer? (:coupling t))
              "ready rows carry a coupling integer"))))

    (testing "blocked --json — array of ls-shape ticket objects"
      (let [{:keys [out]} (run-knot tmp "blocked" "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)))
        (is (seq (:data envelope)))
        (doseq [t (:data envelope)]
          (assert-ticket-payload-shape!
           t (str "blocked --json " (:id t)) {:body? false})
          (is (integer? (:leverage t))
              "blocked rows carry a leverage integer")
          (is (integer? (:coupling t))
              "blocked rows carry a coupling integer"))))

    (testing "closed --json — array of ls-shape ticket objects"
      (let [{:keys [out]} (run-knot tmp "closed" "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)))
        (is (seq (:data envelope)))
        (doseq [t (:data envelope)]
          (assert-ticket-payload-shape!
           t (str "closed --json " (:id t)) {:body? false})
          (is (string? (:closed t))
              "closed-shape entries additionally carry :closed timestamp")
          (is (not (contains? t :leverage))
              "closed rows carry NO leverage field — not on closed in v1")
          (is (not (contains? t :coupling))
              "closed rows carry NO coupling field — not on closed in v1"))))))

(deftest data-shape-show-test
  ;; Pin AC#2 for show --json: single object envelope. Body included.
  ;; With inverse-section neighbors (deps/links pre-seeded), the four
  ;; computed inverse arrays appear at the top level under :data.
  (with-tmp tmp
    (let [{:keys [tagged-id blocker-id blocked-id]} (seed-read-fixture! tmp)]
      (testing "show --json — bare ticket payload"
        (let [{:keys [out]} (run-knot tmp "show" tagged-id "--json")
              envelope (parse-envelope out)]
          (is (map? (:data envelope))
              "show :data is a single ticket object, not an array")
          (assert-ticket-payload-shape!
           (:data envelope) "show --json (tagged)" {:body? true})
          (is (= ["p0" "auth"] (get-in envelope [:data :tags]))
              "tags round-trip in declaration order")
          (is (= ["JIRA-1"] (get-in envelope [:data :external_refs])))))

      (testing "show --json — ticket with blocker emits :blocking inverse"
        ;; The blocker-side ticket has a `:blocking` inverse (the
        ;; blocked ticket points at it). Pinning the inverse-array
        ;; shape: each entry is `{id, title, status}` for resolved
        ;; refs.
        (let [{:keys [out]} (run-knot tmp "show" blocker-id "--json")
              envelope (parse-envelope out)
              blocking (get-in envelope [:data :blocking])]
          (is (vector? blocking))
          (is (= 1 (count blocking)))
          (is (= blocked-id (:id (first blocking))))
          (is (string? (:title (first blocking))))
          (is (string? (:status (first blocking)))))))))

(deftest data-shape-prime-test
  ;; Pin AC#2 for prime --json: object envelope with a fixed top-level
  ;; key set that downstream agents iterate against. Snake_case keys.
  (with-tmp tmp
    (seed-read-fixture! tmp)
    (run-knot tmp "create" "in-progress-one")
    (let [{:keys [out]} (run-knot tmp "prime" "--json")
          envelope (parse-envelope out)
          data     (:data envelope)]
      (testing "prime --json — top-level keys + types"
        (is (map? data))
        (is (map? (:project data)))
        (is (vector? (:in_progress data)))
        (is (vector? (:ready_to_close data)))
        (is (vector? (:ready data)))
        (is (boolean? (:ready_truncated data)))
        (is (integer? (:ready_remaining data)))
        (is (vector? (:recently_closed data))))

      (testing "prime --json — :project sub-keys"
        (let [project (:project data)]
          (is (boolean? (:found project)))
          (is (string? (:prefix project)))
          (is (integer? (:live_count project)))
          (is (integer? (:archive_count project)))))

      (testing "prime --json — :ready entries are compact ticket shape (no body)"
        (let [ready (:ready data)]
          (is (seq ready) "fixture must populate ready")
          (doseq [t ready]
            (is (string? (:id t)))
            (is (string? (:title t)))
            (is (not (contains? t :body))
                "prime ticket entries are body-less compact shape"))))

      (testing "prime --json — :recently_closed entries"
        (let [closed (:recently_closed data)]
          (is (seq closed) "fixture closes one ticket; expect a recently_closed entry")
          (doseq [c closed]
            (is (string? (:id c)))
            (is (string? (:title c)))))))))

(deftest data-shape-info-test
  ;; Pin AC#2 for info --json: object envelope with five fixed
  ;; top-level sections (project, paths, defaults, allowed_values,
  ;; counts). Each section is a map of snake_case keys.
  (with-tmp tmp
    (run-knot tmp "create" "Hello")
    (let [{:keys [out]} (run-knot tmp "info" "--json")
          envelope (parse-envelope out)
          data     (:data envelope)]
      (testing "info --json — five top-level sections"
        (is (map? (:project data)))
        (is (map? (:paths data)))
        (is (map? (:defaults data)))
        (is (map? (:allowed_values data)))
        (is (map? (:counts data))))

      (testing "info --json — :project sub-keys"
        (let [{:keys [knot_version prefix config_present]} (:project data)]
          (is (string? knot_version))
          (is (string? prefix))
          (is (boolean? config_present))))

      (testing "info --json — :allowed_values sub-keys"
        (let [{:keys [statuses active_status terminal_statuses
                      types modes afk_mode priority_range]}
              (:allowed_values data)]
          (is (vector? statuses))
          (is (string? active_status))
          (is (vector? terminal_statuses))
          (is (vector? types))
          (is (vector? modes))
          (is (string? afk_mode))
          (is (integer? (:min priority_range)))
          (is (integer? (:max priority_range)))))

      (testing "info --json — :counts sub-keys"
        (let [{:keys [live_count archive_count total_count]} (:counts data)]
          (is (integer? live_count))
          (is (integer? archive_count))
          (is (integer? total_count))
          (is (= total_count (+ live_count archive_count))))))))

(deftest data-shape-check-test
  ;; Pin AC#2 for check --json: object envelope with `:issues` (array)
  ;; and `:scanned` (map of counts). Issue entries carry :severity,
  ;; :code, :ids, :message at minimum (additional keys pass through).
  (testing "check --json on a clean project — empty issues"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [out]} (run-knot tmp "check" "--json")
            envelope (parse-envelope out)
            data     (:data envelope)]
        (is (vector? (:issues data)))
        (is (= [] (:issues data)) "clean project produces no issues")
        (is (map? (:scanned data)))
        (is (integer? (:live    (:scanned data)))
            "check :scanned uses bare keys (:live/:archive), not :live_count")
        (is (integer? (:archive (:scanned data)))))))

  (testing "check --json on a project with an invalid_status — issue entry shape"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "kno-01abc111111--corrupt.md"))
            (str "---\nid: kno-01abc111111\ntitle: Corrupt\n"
                 "status: not-a-real-status\ntype: task\npriority: 2\n"
                 "mode: hitl\ncreated: 2026-01-01T00:00:00Z\n"
                 "updated: 2026-01-01T00:00:00Z\n---\n\n# Corrupt\n"))
      (let [{:keys [out]} (run-knot tmp "check" "--json")
            envelope (parse-envelope out)
            issues   (get-in envelope [:data :issues])
            issue    (first (filter #(= "invalid_status" (:code %)) issues))]
        (is (some? issue) "invalid_status issue must appear")
        (is (= "error" (:severity issue))
            "severities serialize as snake_case strings, not keywords")
        (is (= "invalid_status" (:code issue)))
        (is (vector? (:ids issue)))
        (is (string? (:message issue)))))))

(deftest data-shape-dep-tree-test
  ;; Pin AC#2 for dep tree --json: object envelope with a recursive
  ;; tree node shape `{id, title?, status?, missing?, seen_before?,
  ;; deps?}`.
  (with-tmp tmp
    (let [{:keys [blocker-id blocked-id]} (seed-read-fixture! tmp)]
      (testing "dep tree --json on a leaf — title + status + empty deps"
        ;; The blocker has no deps of its own; pin a leaf node's shape.
        (let [{:keys [out]} (run-knot tmp "dep" "tree" blocker-id "--json")
              envelope (parse-envelope out)
              root     (:data envelope)]
          (is (= blocker-id (:id root)))
          (is (string? (:title root)))
          (is (string? (:status root)))
          (is (= [] (:deps root))
              "leaf nodes carry :deps as []")
          (is (not (contains? root :missing)))
          (is (not (contains? root :seen_before)))))

      (testing "dep tree --json on a node with children — recurses"
        (let [{:keys [out]} (run-knot tmp "dep" "tree" blocked-id "--json")
              envelope (parse-envelope out)
              root     (:data envelope)
              child    (first (:deps root))]
          (is (= blocked-id (:id root)))
          (is (vector? (:deps root)))
          (is (= 1 (count (:deps root))))
          (is (= blocker-id (:id child)))
          (is (string? (:title child)))
          (is (= [] (:deps child))
              "leaf children also carry :deps as []")))

      (testing "dep tree --json on a missing root — only :id and :missing"
        (let [{:keys [out]} (run-knot tmp "dep" "tree" "kno-no-such" "--json")
              envelope (parse-envelope out)
              root     (:data envelope)]
          (is (= "kno-no-such" (:id root)))
          (is (true? (:missing root)))
          (is (not (contains? root :title)))
          (is (not (contains? root :deps))
              "missing nodes do not recurse"))))))

(deftest data-shape-single-ticket-mutators-test
  ;; Pin AC#2 for the mutators routed through `output/touched-ticket-json`:
  ;; create/start/status/close/reopen/dep/undep/add-note/update each
  ;; emit a single ticket payload (body-included) under :data with the
  ;; canonical ticket shape. Post-mutation specifics (status flips,
  ;; deps, etc.) are pinned in cli_test/integration_test already; here
  ;; the focus is the *envelope shape*, not the mutation outcome.
  (testing "create --json — single ticket payload"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "create --json" {:body? true})
        (is (= "Hello" (get-in envelope [:data :title]))))))

  (testing "start --json — single ticket payload, status=in_progress"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "start" id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "start --json" {:body? true})
        (is (= "in_progress" (get-in envelope [:data :status]))))))

  (testing "status --json — single ticket payload, new status"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "status" id "in_progress" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "status --json" {:body? true})
        (is (= "in_progress" (get-in envelope [:data :status]))))))

  (testing "close --json — single ticket payload + meta.archived_to"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "close" id "--summary" "x" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "close --json" {:body? true})
        (is (= "closed" (get-in envelope [:data :status])))
        (is (string? (get-in envelope [:data :closed]))
            "closed-shape ticket carries the :closed timestamp")
        (is (string? (get-in envelope [:meta :archived_to]))))))

  (testing "reopen --json — single ticket payload, status flipped open"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            _  (run-knot tmp "close" id "--summary" "x")
            {:keys [out]} (run-knot tmp "reopen" id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "reopen --json" {:body? true})
        (is (= "open" (get-in envelope [:data :status]))))))

  (testing "dep --json — single (from) ticket payload with updated :deps"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            {:keys [out]} (run-knot tmp "dep" a-id b-id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "dep --json" {:body? true})
        (is (= a-id (get-in envelope [:data :id])))
        (is (= [b-id] (get-in envelope [:data :deps]))))))

  (testing "undep --json — single (from) ticket payload, deps drained"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "dep" a-id b-id)
            {:keys [out]} (run-knot tmp "undep" a-id b-id "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "undep --json" {:body? true})
        (is (= [] (get-in envelope [:data :deps]))))))

  (testing "add-note --json — single ticket payload with note in body"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "add-note" id "first note" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "add-note --json" {:body? true})
        (is (str/includes? (get-in envelope [:data :body]) "first note")))))

  (testing "update --json — single ticket payload, no :meta"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "update" id "--priority" "1" "--json")
            envelope (parse-envelope out)]
        (assert-ticket-payload-shape!
         (:data envelope) "update --json" {:body? true})
        (is (= 1 (get-in envelope [:data :priority])))
        (is (not (contains? envelope :meta))
            "update never archives — no :meta slot")))))

(deftest data-shape-multi-ticket-and-migrate-test
  ;; Pin AC#2 for the remaining mutators: link/unlink return an array
  ;; of body-less tickets; migrate-ac returns a counts triple
  ;; `{migrated, unchanged, total}`.
  (testing "link --json — array of ls-shape (body-less) ticket payloads"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            {:keys [out]} (run-knot tmp "link" a-id b-id "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)) "link :data is an array")
        (is (= 2 (count (:data envelope))))
        (is (= #{a-id b-id} (set (map :id (:data envelope)))))
        (doseq [t (:data envelope)]
          (assert-ticket-payload-shape!
           t (str "link --json " (:id t)) {:body? false})
          (is (= 1 (count (:links t)))
              "linked peer's id appears in :links")))))

  (testing "unlink --json — array of post-mutation ticket payloads"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "link" a-id b-id)
            {:keys [out]} (run-knot tmp "unlink" a-id b-id "--json")
            envelope (parse-envelope out)]
        (is (vector? (:data envelope)))
        (is (= 2 (count (:data envelope))))
        (doseq [t (:data envelope)]
          (assert-ticket-payload-shape!
           t (str "unlink --json " (:id t)) {:body? false})
          (is (= [] (:links t))
              "after unlink, :links is the empty default vector")))))

  (testing "migrate-ac --json — counts triple"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [out]} (run-knot tmp "migrate-ac" "--json")
            envelope (parse-envelope out)
            data     (:data envelope)]
        (is (map? data))
        (is (integer? (:migrated data)))
        (is (integer? (:unchanged data)))
        (is (integer? (:total data)))
        (is (= (:total data) (+ (:migrated data) (:unchanged data)))
            "total == migrated + unchanged invariant")
        (is (not (contains? data :tickets))
            "migrate-ac envelope is intentionally compact — counts only, no per-ticket payloads")))))

(deftest envelope-invariants-mutating-commands-test
  ;; Pin AC#1: schema_version=1, ok present, data XOR error for every
  ;; mutating --json command. Each mutator runs against a fresh tmp
  ;; project — keeps the assertions independent of execution order.
  (testing "create --json — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello" "--json")]
        (assert-envelope-invariants! (parse-envelope out) "create --json"))))

  (testing "start --json — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "start" id "--json")]
        (assert-envelope-invariants! (parse-envelope out) "start --json"))))

  (testing "status --json (non-terminal) — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "status" id "in_progress" "--json")]
        (assert-envelope-invariants! (parse-envelope out) "status --json"))))

  (testing "close --json — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "close" id "--summary" "x" "--json")]
        (assert-envelope-invariants! (parse-envelope out) "close --json"))))

  (testing "reopen --json — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            _  (run-knot tmp "close" id "--summary" "x")
            {:keys [out]} (run-knot tmp "reopen" id "--json")]
        (assert-envelope-invariants! (parse-envelope out) "reopen --json"))))

  (testing "dep --json — central envelope invariants"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            {:keys [out]} (run-knot tmp "dep" a-id b-id "--json")]
        (assert-envelope-invariants! (parse-envelope out) "dep --json"))))

  (testing "undep --json — central envelope invariants"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "dep" a-id b-id)
            {:keys [out]} (run-knot tmp "undep" a-id b-id "--json")]
        (assert-envelope-invariants! (parse-envelope out) "undep --json"))))

  (testing "link --json — central envelope invariants"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            {:keys [out]} (run-knot tmp "link" a-id b-id "--json")]
        (assert-envelope-invariants! (parse-envelope out) "link --json"))))

  (testing "unlink --json — central envelope invariants"
    (with-tmp tmp
      (let [{a-out :out} (run-knot tmp "create" "A")
            {b-out :out} (run-knot tmp "create" "B")
            a-id (id-of a-out "a")
            b-id (id-of b-out "b")
            _    (run-knot tmp "link" a-id b-id)
            {:keys [out]} (run-knot tmp "unlink" a-id b-id "--json")]
        (assert-envelope-invariants! (parse-envelope out) "unlink --json"))))

  (testing "add-note --json — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "add-note" id "noted" "--json")]
        (assert-envelope-invariants! (parse-envelope out) "add-note --json"))))

  (testing "update --json — central envelope invariants"
    (with-tmp tmp
      (let [{:keys [out]} (run-knot tmp "create" "Hello")
            id (id-of out "hello")
            {:keys [out]} (run-knot tmp "update" id "--priority" "1" "--json")]
        (assert-envelope-invariants! (parse-envelope out) "update --json"))))

  (testing "migrate-ac --json — central envelope invariants"
    (with-tmp tmp
      (run-knot tmp "create" "Hello")
      (let [{:keys [out]} (run-knot tmp "migrate-ac" "--json")]
        (assert-envelope-invariants! (parse-envelope out) "migrate-ac --json")))))

(deftest check-exception-ok-false-with-data-test
  ;; Pin AC#1 (the `knot check` exception): when integrity errors exist,
  ;; `check --json` returns the documented `{ok:false, data:{...}}`
  ;; envelope. `:ok` mirrors a health verdict here, not a request
  ;; outcome — without this carve-out the central XOR rule would
  ;; reject a contract the runtime intentionally emits.
  (testing "check --json on a project with errors emits ok:false WITH data"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      ;; Hand-write a ticket whose status is not in the project's
      ;; allowed `:statuses` set. This drives an `:invalid_status`
      ;; error from `knot check` without going through the CLI (which
      ;; would reject the bad status at the boundary).
      (spit (str (fs/path tmp ".tickets" "kno-01abc111111--corrupt.md"))
            (str "---\n"
                 "id: kno-01abc111111\n"
                 "title: Corrupt\n"
                 "status: not-a-real-status\n"
                 "type: task\n"
                 "priority: 2\n"
                 "mode: hitl\n"
                 "created: 2026-01-01T00:00:00Z\n"
                 "updated: 2026-01-01T00:00:00Z\n"
                 "---\n\n"
                 "# Corrupt\n"))
      (let [{:keys [exit out err]} (run-knot tmp "check" "--json")
            envelope (parse-envelope out)]
        (is (= 1 exit) (str "check err=" err))
        (is (str/blank? err))
        (assert-envelope-invariants! envelope "check --json (errors)" {:check? true})
        (is (false? (:ok envelope)))
        (is (contains? envelope :data)
            "check exit-1 envelope carries :data alongside ok:false")
        (is (vector? (get-in envelope [:data :issues])))
        (is (some #(= "invalid_status" (:code %))
                  (get-in envelope [:data :issues]))
            "the invalid_status error must appear in the issues array")))))

(deftest envelope-invariants-read-commands-test
  ;; Pin AC#1: schema_version=1, ok present, data XOR error for every
  ;; read --json command. Loops over a curated command set against one
  ;; seeded fixture so a new --json read command added later forces a
  ;; one-line edit here, not a new deftest.
  (with-tmp tmp
    (let [{:keys [tagged-id blocker-id]} (seed-read-fixture! tmp)
          read-commands {"list --json"     ["list" "--json"]
                         "ready --json"    ["ready" "--json"]
                         "blocked --json"  ["blocked" "--json"]
                         "closed --json"   ["closed" "--json"]
                         "prime --json"    ["prime" "--json"]
                         "info --json"     ["info" "--json"]
                         "check --json"    ["check" "--json"]
                         "show --json"     ["show" tagged-id "--json"]
                         "dep tree --json" ["dep" "tree" blocker-id "--json"]}]
      (doseq [[label argv] read-commands]
        (testing (str label " — central envelope invariants")
          (let [{:keys [out err]} (apply run-knot tmp argv)
                envelope (parse-envelope out)]
            (is (str/blank? err)
                (str label ": stderr should be empty on success, got: "
                     (pr-str err)))
            (assert-envelope-invariants! envelope label)
            (is (true? (:ok envelope))
                (str label ": clean fixture should produce ok:true"))))))))
