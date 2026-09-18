# Ticket Documents Implementation Plan

> **For agentic workers:** Execute task by task in order. Steps use checkbox
> (`- [ ]`) syntax for tracking. Read the spec alongside this plan. Do not
> proceed past a STOP step without approval.

**Goal:** A ticket can carry any number of independently addressable documents, each with a title, a configured type and a body, managed entirely through the `knot` CLI.
**Architecture:** A document is a markdown file with YAML frontmatter at `<tickets-dir>/docs/<owning-ticket-id>/<document-id>--<slug>.md`. The ticket loader's non-recursive `*.md` glob cannot see it, so the corpora never contaminate each other. The ticket-to-documents relation is computed by reading documents backwards; nothing is stored on the ticket.
**Tech Stack:** Babashka / Clojure, `clj-yaml`, `cheshire`, `babashka.fs`, `babashka.cli`, `clojure.test`.
**Spec:** `docs/ai-assistant-ideation/kno-01m2mdq6ecfh-ticket-documents-spec.md`
**Test route:** strict — this is behaviour change in shared core code with a persistence layer and two published contracts; every task defaults to failing-test-first.

## Global Constraints

- Read `.clj` files through `clj-surgeon`, never `Read`/`grep`/`sed`/`cat` (AGENTS.md hard rule).
- Never hand-edit files under `.tickets/`; drive every ticket read and write through the `knot` CLI.
- No AI attribution in commit messages: no `Co-Authored-By`, no "Generated with", no AI emoji. The `commit-msg` hook rejects them, so a task that writes one cannot pass its own verification.
- Conventional-commit subjects: `<type>(<scope>): <description>`.
- `bb test` and `clj-kondo --lint src test` gate every commit touching `src/`, `test/`, `bb.edn` or CI.
- Never copy the atomic-write or create-exclusive logic into a second implementation.
- Never reuse `store/find-by-id-glob-all` for documents, and never put the owning ticket id in a leading filename segment.
- Never weaken or skip a guard to make a step pass.
- Never hand-edit `knot.schema.json`; regenerate with `bb gen:schema`.
- Assert path shape with `fs/components`, never by embedding `/` or `\` in a string comparison. `--json` path fields are unixified and may be compared as strings.
- Config is the only source of statuses, types, modes and doc-types. Never hardcode a member.

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `src/knot/config.clj` | Modify | Add `:doc-types` / `:default-doc-type` defaults, register in `known-keys`, validate membership |
| `src/knot/doc.clj` | Create | Pure document module: frontmatter shape, id generation, filename derivation. No I/O. New file because `knot.ticket` is the ticket's shape and a second record type does not belong in it |
| `src/knot/store.clj` | Modify | Extract path-independent write primitives; add docs-scoped load/save/resolve/delete |
| `src/knot/query.clj` | Modify | Backward read from documents to owning ticket |
| `src/knot/check.clj` | Modify | Third scan arm; four document validators; legacy-heading warning |
| `src/knot/cli.clj` | Modify | `document` command handlers; document-aware `delete` |
| `src/knot/help.clj` | Modify | `:document` parent entry plus one entry per subcommand; help `:notes` |
| `src/knot/output.clj` | Modify | `## Documents` render; `documents` array in `show --json`; `docs` arm in check's scanned counts |
| `src/knot/cli.clj` (`info-data`) | Modify | Third pinned shape: `info`'s `allowed_values` gains the two new config keys |
| `src/knot/ticket.clj` | Modify | Sixth row in `reserved-section-owners` |
| `src/knot/main.clj` | Modify | `document` dispatch; new error codes |
| `test/knot/doc_test.clj` | Create | Pure round-trip and filename derivation. Must contain no `with-redefs`, so it stays in the parallel phase |
| `test/knot/config_test.clj` | Modify | Doc-type config defaults and validation |
| `test/knot/store_test.clj` | Modify | Document store operations, sibling safety |
| `test/knot/check_test.clj` | Modify | The four document check arms and the legacy-heading warning |
| `test/knot/cli_test.clj` | Modify | Document command group; cascade fault injection (already serial — 14 markers) |
| `test/knot/integration_test.clj` | Modify | End-to-end document lifecycle through the real CLI |
| `test/knot/json_contract_test.clj` | Modify | Three pinned envelope shapes |
| `test/knot/doc_codes_test.clj` | Modify | Extend extractor sources; add the guard self-test |
| `resources/knot/skill/references/json.md` | Modify | Error-code and check-code catalogue rows |
| `README.md` | Modify | Two `.knot.edn` rows |
| `docs/adr/0022-cascade-removes-destroyed-dependents-last.md` | Create | Successor to ADR 0008 on write ordering |
| `docs/adr/0008-delete-defaults-to-leaf-only.md` | Modify | Inline marker on the superseded ordering clause |
| `knot.schema.json` | Regenerate | `bb gen:schema` |

## Coverage

| Spec item | Task(s) |
|-----------|---------|
| R1, R3, R9 | 2 |
| R2 | 5 |
| R4, R35 | 1 |
| R5, R6, R7, R30, R31, R32, R33 | 7 |
| R8, R10, R15, R22, R23, R25 | 4 |
| R11, R12, R38 | 6 |
| R13 | 4, 6 |
| R14 | 4 |
| R16 | 6 |
| R17 | 9 |
| R18 | 7 |
| R19, R20, R21, R39 | 8 |
| R24 | 6 |
| R26 | 7 (pull: help registry and notes), 10 (pointer: skill and catalogue). Push is deliberately unchanged — no task touches `knot prime`, and the spec's Scope excludes it |
| R27, R28, R34 | 10 |
| R29 | 1, 8 (third pinned shape), 10 |
| R36 | 2, 9 |
| R37 | 3 |
| AC-1, AC-3 | 4 |
| AC-2 | 2 |
| AC-4, AC-6, AC-7, AC-8, AC-9, AC-9a, AC-9b, AC-9c, AC-9d | 7 |
| AC-5, AC-16b, AC-17, AC-23, AC-24, AC-25 | 8 |
| AC-10, AC-11, AC-12, AC-12a, AC-13, AC-14, AC-16, AC-16a | 6 |
| AC-14a, AC-14b, AC-15 | 1 |
| AC-14c | 10 |
| AC-18, AC-21 | 4 |
| AC-19, AC-20 | 9 |
| AC-22 | 7 |

## Carried Assumptions

- A document belongs to exactly one ticket and ownership does not transfer (MEDIUM) — affects Task 4; no reassign command is planned.
- Document ordering within a ticket is not significant; listings order by identifier (LOW) — affects Task 4 and Task 8.
- A blank body produces a valid empty document rather than an error or a no-op, deliberately differing from `add-note`'s no-op (MEDIUM) — affects Task 7.
- **Binary on stdin is a reachable path with a stated outcome** (MEDIUM) — affects Task 7. R31 mandates stdin as an input layer while "documents are text" is only a scope *exclusion*, so piping a PNG is reachable. Task 7 refuses input that is not valid UTF-8, with a named error, rather than writing a file whose round-trip guarantee does not hold. Out-of-scope for a feature is fine; undefined behaviour on a reachable input is not.

## Carried Findings

- **Finding 4 (open, P2):** the error-code table's per-command `Commands` column in `references/json.md` has no guard behind it — `doc_codes_test` pins the code set in both directions but its docstring states the attribution column is prose and unchecked. Task 10 updates the column; nothing verifies it. Left as a known gap, not silently inherited.
- **Finding 5 (open, P2):** `quality-engineer` holds two standing conditions at the implementation gate — AC-20 unaccepted without the fault-injection assertion in the diff (Task 9), AC-14c unaccepted if the catalogue guard is run but not itself tested (Task 10). It also notes R30 reads SHOULD where MUST would cost nothing; Task 7 Step 1 raises that as a STOP.

---

### Task 1: Document type configuration

**Implements:** R4, R35, AC-14a, AC-14b, AC-15
**Depends on:** none   **Parallel with:** Task 2, Task 3

**Files:**
- Modify: `src/knot/config.clj` — `default-config`, `known-keys`, `validate!`
- Test: `test/knot/config_test.clj`

**Interfaces:**
- Consumes: nothing.
- Produces: config keys `:doc-types` (vector of non-blank strings, default `["spec" "plan" "other"]`) and `:default-doc-type` (string, default `"other"`), readable from the merged config map by every later task.

**Guardrails:** An unregistered `.knot.edn` key draws a warning and is silently dropped, so `known-keys` must be updated in the same commit as the default. Never hardcode a doc-type member outside `default-config`.

- [ ] **Step 1: Write the failing tests**
```clojure
(deftest doc-type-defaults-test
  (testing "defaults carry a non-empty allow-list and a member default"
    (let [c (config/defaults)]
      (is (= ["spec" "plan" "other"] (:doc-types c)))
      (is (= "other" (:default-doc-type c)))
      (is (contains? (set (:doc-types c)) (:default-doc-type c))))))

(deftest doc-type-known-keys-test
  (testing "both keys are recognised, so neither is warned about and dropped"
    (with-tmp tmp
      (spit (str (fs/path tmp ".knot.edn"))
            (pr-str {:doc-types ["spec" "other"] :default-doc-type "other"}))
      (let [{:keys [config]} (config/discover tmp)]
        (is (= ["spec" "other"] (:doc-types config)))
        (is (= "other" (:default-doc-type config)))))))

(deftest doc-type-validation-test
  (testing "a default outside its own list fails at command start"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":default-doc-type must be one of :doc-types"
         (config/validate! (assoc (config/defaults)
                                  :doc-types ["spec"] :default-doc-type "other")))))
  (testing "an empty allow-list is refused rather than silently disabling the gate"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":doc-types must be a non-empty list"
         (config/validate! (assoc (config/defaults) :doc-types []))))))
```
- [ ] **Step 2: Run the tests to verify they fail**
Run: `bb test 2>&1 | tail -3`
Expected: non-zero failures naming `doc-type-defaults-test`, `doc-type-known-keys-test`, `doc-type-validation-test`.
- [ ] **Step 3: Add the defaults and register the keys**
In `default-config`, add after `:default-mode`:
```clojure
   :doc-types         ["spec" "plan" "other"]
   :default-doc-type  "other"
```
In `known-keys`, add `:doc-types :default-doc-type` — taking the set from fourteen entries to sixteen.
- [ ] **Step 4: Add the validation clause**
In `validate!`, destructure `doc-types default-doc-type` alongside the existing keys and add, mirroring the `:types` / `:default-type` pair exactly:
```clojure
    (when-not (list-of-non-blank-strings? doc-types)
      (throw (ex-info ".knot.edn :doc-types must be a non-empty list of strings" {})))
    (when-not ((set doc-types) default-doc-type)
      (throw (ex-info ".knot.edn :default-doc-type must be one of :doc-types" {})))
```
- [ ] **Step 5: Run the tests to verify they pass**
Run: `bb test 2>&1 | tail -3`
Expected: `0 failures, 0 errors`.
- [ ] **Step 6: Lint**
Run: `clj-kondo --lint src test`
Expected: `errors: 0, warnings: 0`.
- [ ] **Step 7: Commit**
```bash
git add src/knot/config.clj test/knot/config_test.clj
dev-gate git commit -m "feat(config): add :doc-types allow-list and :default-doc-type (R4, R35)"
```

---

### Task 2: Pure document module

**Implements:** R1, R3, R9, R36, AC-2
**Depends on:** none   **Parallel with:** Task 1, Task 3

**Files:**
- Create: `src/knot/doc.clj` — a second record type needs its own pure module; `knot.ticket` is the ticket's shape and folding a document into it would put two records in one namespace
- Create: `test/knot/doc_test.clj`

**Interfaces:**
- Consumes: `knot.ticket/parse`, `knot.ticket/render`, `knot.ticket/derive-slug` — the frontmatter envelope is identical, so the document module reuses them rather than reimplementing YAML handling.
- Produces:
  - `(doc/generate-id prefix)` → `"<prefix>-d<10 ts><2 rand>"`, a distinct segment so a document id can never be mistaken for a ticket id
  - `(doc/filename id title)` → `"<id>--<slug>.md"`
  - `(doc/id-of filename)` → the leading id segment, or nil
  - `(doc/valid? doc)` → boolean over required frontmatter keys

**Guardrails:** This namespace performs no I/O. **It must contain no `with-redefs`, `alter-var-root` or `System/setProperty`, in either source or test** — `unsafe-ns?` scans the whole test file and one occurrence moves the namespace into the serial phase permanently (R36).

- [ ] **Step 1: Write the failing round-trip test**
```clojure
(def ^:private adversarial-body
  "A body that exercises every shape the frontmatter envelope could mangle."
  (str "Leading text.\n\n"
       "---\n\n"
       "## A heading that is not a section knot owns\n\n"
       "Body text.   \n"))

(deftest document-round-trip-test
  (testing "an adversarial body survives render then parse byte-for-byte"
    (let [d {:frontmatter {:id "kno-d01abc" :ticket "kno-01xyz"
                           :title "T" :type "spec"
                           :created "2026-01-01T00:00:00Z"
                           :updated "2026-01-01T00:00:00Z"}
             :body adversarial-body}
          round-tripped (ticket/parse (ticket/render d))]
      (is (= adversarial-body (:body round-tripped)))
      (is (= (:frontmatter d) (:frontmatter round-tripped))))))

(deftest document-id-is-distinguishable-test
  (testing "a document id cannot be mistaken for a ticket id"
    (let [did (doc/generate-id "kno")]
      (is (re-matches #"kno-d[0-9a-z]{12}" did))
      (is (not (re-matches #"kno-[0-9a-z]{12}" did))))))

(deftest document-filename-test
  (testing "the document's own id leads the filename, never the owning ticket"
    (is (= "kno-d01abc--a-design-note" (subs (doc/filename "kno-d01abc" "A design note") 0 26)))
    (is (str/starts-with? (doc/filename "kno-d01abc" "x") "kno-d01abc--"))))
```
- [ ] **Step 2: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failures naming the three tests, `knot.doc` not found.
- [ ] **Step 3: Write the module**
```clojure
(ns knot.doc
  "Pure module for documents attached to tickets: frontmatter shape,
   identifier generation and filename derivation. The frontmatter envelope
   is the ticket's, so parse/render are reused from knot.ticket. No I/O."
  (:require [clojure.string :as str]
            [knot.ticket :as ticket]))

(def ^:private doc-marker
  "The segment that distinguishes a document id from a ticket id. A human
   reading an id can tell which corpus it addresses, and a mistyped command
   fails loudly instead of resolving in the wrong one."
  "d")

(defn generate-id
  "Generate a document id: `<prefix>-d<12 Crockford base32 chars>`. Delegates
   to the ticket factory for the monotonic suffix, then marks it."
  [prefix]
  (let [tid (ticket/generate-id prefix)
        [p suffix] (str/split tid #"-" 2)]
    (str p "-" doc-marker suffix)))

(def required-fields
  "Frontmatter keys every stored document carries."
  [:id :ticket :title :type :created :updated])

(defn valid?
  "True when `doc`'s frontmatter carries every required field non-blank."
  [doc]
  (let [fm (:frontmatter doc)]
    (every? (fn [k] (let [v (get fm k)]
                      (and (string? v) (not (str/blank? v)))))
            required-fields)))

(defn filename
  "`<document-id>--<slug>.md`. The document's OWN id leads, so the store's
   straggler sweep keeps operating on a set that is one record's files."
  [id title]
  (str id "--" (ticket/derive-slug title) ".md"))

(defn id-of
  "The leading id segment of a document filename, or nil when it does not
   look like one."
  [fname]
  (when (string? fname)
    (second (re-matches #"^([^-]+-d[0-9a-z]+)--.*\.md$" fname))))
```
- [ ] **Step 4: Run to verify pass**
Run: `bb test 2>&1 | tail -3`
Expected: `0 failures, 0 errors`.
- [ ] **Step 5: Assert the namespace stayed parallel**
Run: `grep -c 'with-redefs\|alter-var-root\|System/setProperty' test/knot/doc_test.clj`
Expected: `0`. A non-zero result means this namespace has joined the serial phase and R36 is violated.
- [ ] **Step 6: Lint and commit**
```bash
clj-kondo --lint src test
git add src/knot/doc.clj test/knot/doc_test.clj
dev-gate git commit -m "feat(doc): pure document module with distinguishable ids (R1, R3, R9)"
```

---

### Task 3: Extract the shared write primitives

**Implements:** R37
**Depends on:** none   **Parallel with:** Task 1, Task 2

**Files:**
- Modify: `src/knot/store.clj` — `atomic-write!`, `atomic-move!`, `create-new-options`
- Test: `test/knot/store_test.clj` (existing tests are the regression surface)

**Interfaces:**
- Consumes: nothing.
- Produces: `atomic-write!`, `atomic-move!` and `write-new!` as non-private vars callable from the document path. `write-new!` wraps the `CREATE_NEW` open and returns `::collision` rather than throwing, so both corpora share one retry shape.

**Guardrails:** This is a pure refactor. **No behaviour change** — `save!` and `save-new!` must produce byte-identical results and every existing store test must pass untouched. Do not move the routing policy; only the write.

- [ ] **Step 1: Confirm the current suite is green before refactoring**
Run: `bb test 2>&1 | tail -3`
Expected: `0 failures, 0 errors`. A refactor started on red cannot be verified.
- [ ] **Step 2: Confirm the moved lines are actually covered**
Run: `grep -n 'FileAlreadyExists\|collision\|max-retries' test/knot/store_test.clj`
Expected: at least one test that genuinely **forces a collision** through `save-new!`. If none exists, the retry loop is untested and the extraction is unverifiable however green the suite is — write that test before Step 2. Green says nothing about coverage of the lines being moved.
- [ ] **Step 3: Pin the error-classification boundary this refactor moves**
`write-new!` returns `::collision` where the inline `try`/`catch` threw `FileAlreadyExistsException`. That changes which failures retry and which propagate, and a non-collision IO failure during the exclusive open is exercised nowhere today. One test, before the extraction:
```clojure
(deftest write-new-classifies-failures-test
  (testing "a collision is retried, any other IO failure propagates"
    (with-tmp tmp
      (let [p (fs/path tmp "x.md")]
        (spit (str p) "existing")
        (is (= ::store/collision (store/write-new! p (.getBytes "new" "UTF-8")))))
      (let [unwritable (fs/path tmp "nodir" "deep" "x.md")]
        (is (thrown? java.io.IOException
                     (store/write-new! unwritable (.getBytes "x" "UTF-8"))))))))
```
- [ ] **Step 4: Promote the primitives**
In `src/knot/store.clj`, change `atomic-write!` and `atomic-move!` from `defn-` to `defn`, and extract the create-exclusive open from `save-new!` into:
```clojure
(defn write-new!
  "Create `path` exclusively and write `bytes`. Returns the path on success
   and `::collision` when the path already exists. The open is atomic
   (POSIX O_CREAT|O_EXCL), so two racing writers see exactly one success.
   Shared by the ticket and document paths; the routing policy that chooses
   `path` is each corpus's own."
  [^Path path ^bytes bytes]
  (fs/create-dirs (fs/parent path))
  (try
    (Files/write path bytes create-new-options)
    (str path)
    (catch FileAlreadyExistsException _ ::collision)))
```
- [ ] **Step 5: Rewrite `save-new!`'s inner write to call it**
Replace the inline `try`/`catch` in `save-new!`'s loop body with `(write-new! target-path rendered)`, keeping the retry loop, the id regeneration and the exhaustion `ex-info` exactly as they are.
- [ ] **Step 6: Verify no behaviour changed**
Run: `bb test 2>&1 | tail -3`
Expected: `0 failures, 0 errors`, with the same test count as Step 1 **plus** the classification test from Step 1b. The count check catches a deleted test, not a weakened one — it is a guard against accidental deletion and nothing more. The classification test is what actually pins the refactor.
- [ ] **Step 7: Lint and commit**
```bash
clj-kondo --lint src test
git add src/knot/store.clj test/knot/store_test.clj
dev-gate git commit -m "refactor(store): share the atomic write primitives across corpora (R37)"
```

---

### Task 4: Document storage operations

**Implements:** R8, R10, R13, R14, R15, R22, R23, R25, AC-1, AC-3, AC-9, AC-18, AC-21
**Depends on:** Task 2, Task 3   **Parallel with:** none

**Files:**
- Modify: `src/knot/store.clj` — document load/save/resolve/delete
- Test: `test/knot/store_test.clj`

**Interfaces:**
- Consumes: `doc/filename`, `doc/id-of`, `doc/generate-id`, `store/atomic-write!`, `store/write-new!`.
- Produces:
  - `(store/docs-dir project-root tickets-dir ticket-id)` → the owner directory path
  - `(store/load-docs-for project-root tickets-dir ticket-id)` → vector of parsed documents, empty for an absent **or** empty directory
  - `(store/load-all-docs project-root tickets-dir)` → every document, for `check`
  - `(store/save-new-doc! project-root tickets-dir gen-id-fn build-fn opts)` → written path; creates **exclusively** via `write-new!`, regenerating the id on collision, bounded by `:max-retries`
  - `(store/save-doc! project-root tickets-dir doc opts)` → written path; **update only**, refusing when no file for that id exists. Recovers the slug from the existing filename so a retitle never renames
  - `(store/resolve-doc project-root tickets-dir input)` → the unique document, throwing `:kind :not-found` or `:kind :ambiguous`
  - `(store/delete-doc! path)` → the path string

**Fixtures:** `mkdoc` in this task's tests **plants a document file directly on disk** — it must not be built on `save-doc!`. These are the failing tests *for* `save-doc!`, and the sibling-safety test is the regression for the hazard that drove the whole storage layout; building its fixture with the function under test would prove materially less. Use `doc/id-of` rather than inventing a parallel id-extraction helper.

**Guardrails:** Never call `find-by-id-glob-all` here. The owner is a directory; the document's own id leads the filename. A retitle recovers the slug from disk, exactly as `save!` does for tickets — renaming would make replace two operations with an interrupted state carrying a duplicate id.

- [ ] **Step 1: Write the failing tests**
```clojure
(deftest sibling-safety-test
  (testing "writing one document leaves every sibling byte-identical"
    (with-tmp tmp
      (let [a (store/save-doc! tmp ".tickets" (mkdoc "kno-d01a" "kno-01t" "A" "spec" "alpha") {})
            b (store/save-doc! tmp ".tickets" (mkdoc "kno-d01b" "kno-01t" "B" "spec" "beta") {})
            a-before (slurp a)]
        (store/save-doc! tmp ".tickets" (mkdoc "kno-d01b" "kno-01t" "B" "plan" "beta v2") {})
        (is (= a-before (slurp a)) "sibling must be untouched")
        (is (fs/exists? b))))))

(deftest retitle-does-not-rename-test
  (testing "a retitle keeps the filename and creates no second file"
    (with-tmp tmp
      (let [p (store/save-doc! tmp ".tickets" (mkdoc "kno-d01a" "kno-01t" "Old" "spec" "x") {})
            _ (store/save-doc! tmp ".tickets" (mkdoc "kno-d01a" "kno-01t" "New" "spec" "x") {})
            dir (store/docs-dir tmp ".tickets" "kno-01t")]
        (is (fs/exists? p))
        (is (= 1 (count (fs/glob dir "*.md"))))))))

(deftest create-is-exclusive-test
  (testing "a colliding id retries rather than overwriting an existing document"
    (with-tmp tmp
      (let [ids  (atom ["kno-d01dup" "kno-d01dup" "kno-d01fresh"])
            gen  #(let [v (first @ids)] (swap! ids rest) v)
            p1   (store/save-new-doc! tmp ".tickets" (constantly "kno-d01dup")
                                      (fn [id] {:doc (mkrec id "kno-01t" "A" "spec" "a")}) {})
            p2   (store/save-new-doc! tmp ".tickets" gen
                                      (fn [id] {:doc (mkrec id "kno-01t" "B" "spec" "b")}) {})]
        (is (not= p1 p2) "the second create must not land on the first's path")
        (is (= "a" (:body (ticket/parse (slurp p1)))) "the first document is untouched")))))

(deftest create-detects-a-collision-under-a-different-owner-test
  (testing "uniqueness is corpus-wide: CREATE_NEW alone cannot see this"
    (with-tmp tmp
      (let [ids (atom ["kno-d01dup" "kno-d01fresh"])
            gen #(let [v (first @ids)] (swap! ids rest) v)
            p1  (store/save-new-doc! tmp ".tickets" (constantly "kno-d01dup")
                                     (fn [id] {:doc (mkrec id "kno-01A" "A" "spec" "a")}) {})
            ;; different OWNER, so the target path differs and CREATE_NEW would succeed
            p2  (store/save-new-doc! tmp ".tickets" gen
                                     (fn [id] {:doc (mkrec id "kno-01B" "B" "spec" "b")}) {})]
        (is (str/includes? (str p2) "kno-d01fresh")
            "the duplicate id must be rejected across owner directories, not just within one")
        (is (= "a" (:body (ticket/parse (slurp p1)))))))))

(deftest update-refuses-a-missing-target-test
  (testing "save-doc! never creates"
    (with-tmp tmp
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/save-doc! tmp ".tickets"
                                    (mkrec "kno-d01nope" "kno-01t" "T" "spec" "b") {}))))))

(deftest absent-and-empty-owner-dir-test
  (testing "absent and empty owner directories both mean no documents"
    (with-tmp tmp
      (is (= [] (store/load-docs-for tmp ".tickets" "kno-01none")))
      (fs/create-dirs (store/docs-dir tmp ".tickets" "kno-01empty"))
      (is (= [] (store/load-docs-for tmp ".tickets" "kno-01empty"))))))

(deftest documents-invisible-to-ticket-loader-test
  (testing "the ticket corpus never sees a document"
    (with-tmp tmp
      (store/save-doc! tmp ".tickets" (mkdoc "kno-d01a" "kno-01t" "A" "spec" "x") {})
      (is (empty? (store/load-all tmp ".tickets"))))))
```
- [ ] **Step 2: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failures naming the four tests.
- [ ] **Step 3: Implement the document operations**
```clojure
(def docs-subdir "docs")

(defn docs-dir
  "The owner directory for `ticket-id`. The owner is a directory rather than
   a filename segment because `save!`'s straggler sweep is correct only when
   the leading globbed segment uniquely identifies one record's files."
  [project-root tickets-dir ticket-id]
  (str (fs/path project-root tickets-dir docs-subdir ticket-id)))

(defn load-docs-for
  "Every document owned by `ticket-id`, ordered by filename. An absent
   directory and an empty one are the same answer: no documents."
  [project-root tickets-dir ticket-id]
  (let [d (docs-dir project-root tickets-dir ticket-id)]
    (if-not (fs/directory? d)
      []
      (->> (fs/glob d "*.md")
           (sort-by (comp str fs/file-name))
           (mapv (comp ticket/parse slurp str))))))

(defn load-all-docs
  "Every document across every owner directory, for whole-project checks."
  [project-root tickets-dir]
  (let [root (fs/path project-root tickets-dir docs-subdir)]
    (if-not (fs/directory? root)
      []
      (->> (fs/glob root "*/*.md")
           (sort-by str)
           (mapv (fn [p] (assoc (ticket/parse (slurp (str p)))
                                :path (str p)
                                :owner-dir (str (fs/file-name (fs/parent p))))))))))
```
- [ ] **Step 4: Implement create and update as separate operations**
Document ids are ULID-monotonic *within a process*; across processes two writers can mint the same id in the same millisecond. Tickets survive that because `save-new!` opens with `CREATE_NEW` and retries with a fresh id. A `save-doc!` that always calls `atomic-write!` would instead **silently overwrite** the colliding document — uniqueness resting on probability rather than structure. Mirror the ticket pair: `save-new-doc!` creates exclusively with retry, `save-doc!` updates in place and refuses a target that does not exist.
```clojure
(defn- doc-path-anywhere
  "Any on-disk path carrying document `id`, under ANY owner directory. The
   document corpus is the whole `docs/` tree, so uniqueness has to be checked
   across it -- mirroring `find-all-paths`, which spans live AND archive for
   tickets. A check scoped to one owner directory cannot see a same-id
   collision filed under a different owning ticket."
  [project-root tickets-dir id]
  (let [root (fs/path project-root tickets-dir docs-subdir)]
    (when (fs/directory? root)
      (first (map str (fs/glob root (str "*/" id "--*.md")))))))

(defn save-new-doc!
  "Create a document exclusively. Per attempt: mint an id, reject it if any
   owner directory already carries it, then open with CREATE_NEW. On either
   collision signal, regenerate and retry, bounded by `:max-retries`.
   Mirrors `save-new!`. Two guards, because they catch different things:
   the corpus-wide check catches a same-id document under a DIFFERENT owner,
   which `CREATE_NEW` cannot see because the target path differs; and
   `CREATE_NEW` catches the same-path race the check cannot, being
   time-of-check-to-time-of-use. Neither alone is sufficient."
  [project-root tickets-dir gen-id-fn build-fn {:keys [now max-retries]
                                                :or {max-retries 10}}]
  (let [now* (or now (now-iso))]
    (loop [attempts 0 last-id nil]
      (when (>= attempts max-retries)
        (throw (ex-info "document id collision retry exhausted"
                        {:kind :id-collision-exhausted
                         :attempts attempts :last-id last-id})))
      (let [id     (gen-id-fn)
            {:keys [doc]} (build-fn id)
            fm     (assoc (:frontmatter doc) :created now* :updated now*)
            target (fs/path (docs-dir project-root tickets-dir (:ticket fm))
                            (doc/filename id (:title fm)))
            bytes  (.getBytes ^String (ticket/render (assoc doc :frontmatter fm))
                              StandardCharsets/UTF_8)]
        (if (or (doc-path-anywhere project-root tickets-dir id)
                (= ::collision (write-new! target bytes)))
          (recur (inc attempts) id)
          (str target))))))
```
This is the one create-time cost that is deliberately corpus-wide rather than owner-scoped. It does not weaken the O(owned) invariant, which governs the **read** hot path (`show`, `document ls`); a create is not that path, and the check is one filename-only glob with no parsing.
- [ ] **Step 5: Implement `save-doc!` as update-only, with slug recovery**
```clojure
(defn- existing-doc-path
  [project-root tickets-dir ticket-id id]
  (let [d (docs-dir project-root tickets-dir ticket-id)]
    (when (fs/directory? d)
      (first (map str (fs/glob d (str id "--*.md")))))))

(defn save-doc!
  "Replace an EXISTING document in place. Refuses when no file carries `id`
   under that owner -- creation is `save-new-doc!`, and an update that
   silently created would let a mistyped id produce a second document whose
   replaced body looks correct. The slug is recovered from the existing
   filename, so a retitle never renames and replace stays one atomic write.
   `:created` is preserved; only `:updated` moves."
  [project-root tickets-dir doc {:keys [now]}]
  (let [fm       (:frontmatter doc)
        {:keys [id ticket]} fm
        existing (existing-doc-path project-root tickets-dir ticket id)]
    (when-not existing
      (throw (ex-info (str "document not found: " id)
                      {:kind :not-found :input id})))
    (let [now*  (or now (now-iso))
          prior (:frontmatter (ticket/parse (slurp existing)))
          fm*   (assoc fm :created (:created prior) :updated now*)
          bytes (.getBytes ^String (ticket/render (assoc doc :frontmatter fm*))
                           StandardCharsets/UTF_8)]
      (atomic-write! (fs/path existing) bytes)
      existing)))
```
- [ ] **Step 6: Implement `resolve-doc` with its own three layers**
```clojure
(defn resolve-doc
  "Resolve `input` to a unique document. Three layers, named explicitly
   rather than inherited: exact id, id prefix, then owning-ticket-plus-title.
   `store/resolve-id`'s final layer splits on the first hyphen to recover a
   bare ULID and would mis-split a document id, so it is not reused.
   The third layer takes an optional owning ticket: with one, an exact title
   match within that ticket resolves, and two documents sharing a title there
   are ambiguous rather than first-match (R22, R23)."
  ([project-root tickets-dir input] (resolve-doc project-root tickets-dir input nil))
  ([project-root tickets-dir input ticket-id]
  (let [all   (load-all-docs project-root tickets-dir)
        by    (fn [pred] (vec (filter pred all)))
        id-of (fn [d] (get-in d [:frontmatter :id]))
        exact (by #(= input (id-of %)))]
    (cond
      (= 1 (count exact)) (first exact)
      :else
      (let [pre (by #(str/starts-with? (or (id-of %) "") input))]
        (case (count pre)
          1 (first pre)
          0 (let [by-title (when ticket-id
                             (by #(and (= ticket-id (get-in % [:frontmatter :ticket]))
                                       (= input (get-in % [:frontmatter :title])))))]
              (case (count by-title)
                1 (first by-title)
                0 (not-found! input)
                (ambiguous! input by-title)))
          (ambiguous! input pre))))))
```
- [ ] **Step 7: Run to verify pass**
Run: `bb test 2>&1 | tail -3`
Expected: `0 failures, 0 errors`.
- [ ] **Step 8: Lint and commit**
```bash
clj-kondo --lint src test
git add src/knot/store.clj test/knot/store_test.clj
dev-gate git commit -m "feat(store): document storage under a per-owner directory (R8, R10, R15)"
```

---

### Task 5: Backward relation

**Implements:** R2
**Depends on:** Task 4   **Parallel with:** none

**Files:**
- Modify: `src/knot/query.clj`
- Test: `test/knot/query_test.clj`

**Interfaces:**
- Consumes: documents as loaded by Task 4.
- Produces: `(query/documents-for docs ticket-id)` → the documents whose `:ticket` equals `ticket-id`, preserving input order. Mirrors `query/children` exactly.

**Guardrails:** Nothing is stored on the ticket. The relation is computed, never persisted.

- [ ] **Step 1: Write the failing test**
```clojure
(deftest documents-for-test
  (testing "documents are matched to their owner by the ticket field"
    (let [docs [{:frontmatter {:id "kno-d01a" :ticket "kno-01x"}}
                {:frontmatter {:id "kno-d01b" :ticket "kno-01y"}}
                {:frontmatter {:id "kno-d01c" :ticket "kno-01x"}}]]
      (is (= ["kno-d01a" "kno-d01c"]
             (mapv #(get-in % [:frontmatter :id]) (query/documents-for docs "kno-01x"))))
      (is (empty? (query/documents-for docs "kno-01none"))))))
```
- [ ] **Step 2: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failure naming `documents-for-test`.
- [ ] **Step 3: Implement, mirroring `children`**
```clojure
(defn documents-for
  "Return documents whose `:ticket` equals `id`. Preserves input order.
   Documents without `:ticket` are skipped (nil-safe). The document side
   owns the edge; nothing is stored on the ticket."
  [docs id]
  (filter (fn [d] (= id (get-in d [:frontmatter :ticket]))) docs))
```
- [ ] **Step 4: Run, lint, commit**
```bash
bb test 2>&1 | tail -3
clj-kondo --lint src test
git add src/knot/query.clj test/knot/query_test.clj
dev-gate git commit -m "feat(query): backward read from documents to owning ticket (R2)"
```
Expected: `0 failures, 0 errors`; `errors: 0, warnings: 0`.

---

### Task 6: Check arms

**Implements:** R11, R12, R13, R16, R24, R38, AC-10, AC-11, AC-12, AC-12a, AC-13, AC-14, AC-16, AC-16a
**Depends on:** Task 1, Task 4   **Parallel with:** Task 5, Task 7

**Files:**
- Modify: `src/knot/check.clj` — third scan arm, four document validators, legacy-heading warning
- Test: `test/knot/check_test.clj`

**Interfaces:**
- Consumes: `store/load-all-docs`, config `:doc-types`.
- Produces: check codes `invalid_doc_type`, `doc_unknown_ticket`, `doc_directory_mismatch`, `duplicate_doc_id`, `legacy_documents_section`; `:scanned` gains a `:docs` count.

**Guardrails:** `check-enum` is **not** reusable — it reads a scalar field on a ticket and its `(seq allowed)` guard skips an empty list. The agreement check runs **before** the orphan check (R12), so a misplaced document yields one issue with one repair. Emit every code as a **literal** `:code :keyword` inside `check.clj`, or `doc_codes_test`'s extractor cannot see it (R27).

- [ ] **Step 1: Write the failing tests**
```clojure
(deftest doc-type-check-test
  (testing "a stored type outside the allow-list is reported under its own code"
    (is (= [:invalid_doc_type]
           (mapv :code (check-docs {:config {:doc-types ["spec"]}}
                                   [(mkdoc-at "kno-01t" "kno-d01a" "other")]))))))

(deftest doc-precedence-test
  (testing "a misplaced document yields exactly one issue, the disagreement"
    (let [issues (check-docs {:config cfg :all-ids #{"kno-01live"}}
                             [(mkdoc-at "kno-01dead" "kno-d01a" "spec"
                                        {:ticket "kno-01live"})])]
      (is (= 1 (count issues)))
      (is (= :doc_directory_mismatch (:code (first issues))))))
  (testing "same dead id in both places is an orphan, not a disagreement"
    (let [issues (check-docs {:config cfg :all-ids #{}}
                             [(mkdoc-at "kno-01dead" "kno-d01a" "spec"
                                        {:ticket "kno-01dead"})])]
      (is (= [:doc_unknown_ticket] (mapv :code issues)))))
  (testing "two DIFFERENT dead ids is a disagreement, not suppressed by the orphan rule"
    (let [issues (check-docs {:config cfg :all-ids #{}}
                             [(mkdoc-at "kno-01deadA" "kno-d01a" "spec"
                                        {:ticket "kno-01deadB"})])]
      (is (= [:doc_directory_mismatch] (mapv :code issues))))))

(deftest legacy-documents-heading-test
  (testing "a pre-existing heading warns and does not fail the check"
    (let [issues (check/run-over {:body "## Documents\n\nold prose\n"})]
      (is (= :warning (:severity (first issues))))
      (is (= :legacy_documents_section (:code (first issues))))
      (is (re-find #"remove" (:message (first issues))))))
  (testing "the warning self-clears once the heading is gone"
    (is (empty? (check/run-over {:body "## Notes\n\nfine\n"})))))
```
- [ ] **Step 2: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failures naming the three tests.
- [ ] **Step 3: Add the third scan arm**
In `scan`, add a `docs` glob collecting into its own slot so a document is never validated as a malformed ticket:
```clojure
        docs-root  (fs/path project-root tickets-dir store/docs-subdir)
        docs-glob  (when (fs/directory? docs-root) (vec (fs/glob docs-root "*/*.md")))
```
and return `:documents (mapv ...)` alongside `:tickets`, with `:scanned` gaining `:docs (count (or docs-glob []))`.
- [ ] **Step 4: Write the document validators**
```clojure
(defn- check-doc-type
  "Per-document: `:type` must appear in `:doc-types`. Not `check-enum` —
   that reads a scalar field on a TICKET, and its `(seq allowed)` guard
   skips validation entirely on an empty list, which is exactly the hole
   `:doc-types`' default exists to close."
  [{:keys [config]} doc]
  (let [{:keys [id type]} (:frontmatter doc)
        allowed (:doc-types config)]
    (when (and type (not ((set allowed) type)))
      [{:severity :error :code :invalid_doc_type :ids [id] :path (:path doc)
        :message (str "document " (pr-str id) " has type " (pr-str type)
                      ", not one of " (pr-str (vec allowed)))}])))

(defn- check-doc-placement
  "Per-document, in precedence order. The agreement check runs FIRST: where
   the owner directory and the authoritative `:ticket` field disagree the
   answer is misplacement, whatever either id resolves to. Only when they
   agree can an unresolvable id mean an orphan. Reversing this yields two
   issues with contradictory repairs for one file."
  [{:keys [all-ids]} doc]
  (let [{:keys [id ticket]} (:frontmatter doc)
        dir (:owner-dir doc)]
    (cond
      (not= dir ticket)
      [{:severity :error :code :doc_directory_mismatch :ids [id] :path (:path doc)
        ;; A document with NO :ticket field lands here rather than in the
        ;; orphan branch, which is correct -- but `(pr-str nil)` renders as
        ;; the literal "nil", so say "has no ticket field" instead.
        :message (str "document " (pr-str id) " sits under " (pr-str dir)
                      (if (nil? ticket)
                        " but has no ticket field"
                        (str " but its ticket field names " (pr-str ticket))))}]

      (not (contains? all-ids ticket))
      [{:severity :error :code :doc_unknown_ticket :ids [id] :path (:path doc)
        :message (str "document " (pr-str id) " names ticket " (pr-str ticket)
                      ", which resolves to no ticket")}])))
```
- [ ] **Step 5: Add the duplicate-id and legacy-heading arms**
```clojure
(defn- check-duplicate-doc-ids
  "Whole-corpus: two files claiming one document id. The backstop for the
   ambiguity resolver's worst input — a hand-copy, or a merge conflict
   resolved by keeping both sides."
  [docs]
  (->> docs
       (group-by #(get-in % [:frontmatter :id]))
       (keep (fn [[id group]]
               (when (< 1 (count group))
                 {:severity :error :code :duplicate_doc_id :ids [id]
                  :message (str "document id " (pr-str id) " is claimed by "
                                (count group) " files: "
                                (str/join ", " (sort (map :path group))))})))
       vec))

(defn- check-legacy-documents-section
  "Per-ticket WARNING: a body that already carried `## Documents` before the
   heading was reserved. Mirrors `check-legacy-acceptance` — warning not
   error, its own code, the remedy named, self-clearing once the heading is
   gone. Erroring would break `check` on upgrade for projects that did
   nothing wrong. The remedy is manual: knot ships no conversion command."
  [_ctx ticket]
  (let [{:keys [id]} (:frontmatter ticket)]
    (when (and id (some #{"Documents"} (ticket/reserved-sections (:body ticket))))
      [{:severity :warning :code :legacy_documents_section :ids [id]
        :message (str "body carries a '## Documents' heading, which knot now "
                      "renders from the document corpus; remove the heading by "
                      "hand and re-add its content with `knot document add`")}])))
```
- [ ] **Step 6: Register the per-ticket arm and wire the per-document arms**
Add `check-legacy-documents-section` to `per-ticket-validators`, and run `check-doc-type` and `check-doc-placement` over `:documents` with `check-duplicate-doc-ids` once over the whole set.
- [ ] **Step 7: Run, lint, commit**
```bash
bb test 2>&1 | tail -3
clj-kondo --lint src test
git add src/knot/check.clj test/knot/check_test.clj
dev-gate git commit -m "feat(check): four document arms with agreement before orphan (R11, R12, R38)"
```
Expected: `0 failures, 0 errors`.

---

### Task 7: Document command group

**Implements:** R5, R6, R7, R18, R30, R31, R32, R33, AC-4, AC-6, AC-7, AC-8, AC-9, AC-9a, AC-9b, AC-9c, AC-9d, AC-22
**Depends on:** Task 1, Task 2, Task 4   **Parallel with:** Task 6

**Files:**
- Modify: `src/knot/cli.clj`, `src/knot/help.clj`, `src/knot/main.clj`
- Test: `test/knot/cli_test.clj`, `test/knot/integration_test.clj`, `test/knot/help_test.clj`

**Interfaces:**
- Consumes: `store/save-doc!`, `store/resolve-doc`, `store/load-docs-for`, `store/delete-doc!`, `doc/generate-id`, and `resolve-note-content` — which is `defn-` at `src/knot/cli.clj:844` and therefore reachable only from inside `knot.cli`. This task's handlers must live in `knot.cli` for that reason. Do not promote it to public to reach it from elsewhere; that is a change to an existing surface nothing here authorizes.
- Produces: registry keys `:document`, `:document/add`, `:document/show`, `:document/put`, `:document/rm`, `:document/ls`; error codes `doc_not_found`, `ambiguous_doc`, `invalid_doc_type`.

**Guardrails:** The command is `knot document`, never `knot doc` or `knot docs` — this repo already uses `doc` to mean documentation in two guards this story must keep green, and `doc_flags_test`'s invocation regex would resolve prose mentioning a `knot doc` command. Replace refuses a missing target; it never upserts. Replace preserves `:created` and bumps `:updated`.

- [ ] **Step 1: STOP — approval required:** R30 reads `SHOULD` for the no-alias rule. `technical-writer` argued MUST would cost nothing, since the alias prohibition is the load-bearing half of the naming decision. Confirm whether to promote R30 to MUST before registering the command group. Nothing after this step runs until answered.
- [ ] **Step 2: Write the failing tests**
```clojure
(deftest document-put-refuses-missing-target-test
  (testing "replace never upserts: a mistyped selector must not create a second document"
    (with-tmp tmp
      (let [r (cli/document-put-cmd (ctx tmp) {:id "kno-dnope" :title "T"
                                               :type "spec" :text "b" :json? true})]
        (is (= "doc_not_found" (get-in (json/parse-string r true) [:error :code])))
        (is (empty? (store/load-all-docs tmp ".tickets")))))))

(deftest document-put-preserves-created-test
  (testing "replace is total over fields but preserves the creation stamp"
    (with-tmp tmp
      (let [p       (create-doc tmp "kno-01t" "Old" "spec" "one")
            created (get-in (ticket/parse (slurp p)) [:frontmatter :created])
            _       (Thread/sleep 5)
            _       (cli/document-put-cmd (ctx tmp) {:id (doc-id p) :title "New"
                                                     :type "plan" :text "two"})
            after   (:frontmatter (ticket/parse (slurp p)))]
        (is (= created (:created after)))
        (is (not= created (:updated after)))
        (is (= "New" (:title after)))
        (is (= "plan" (:type after)))))))

(deftest document-rm-leaves-siblings-test
  (testing "delete removes exactly one and leaves siblings byte-identical"
    (with-tmp tmp
      (let [a (create-doc tmp "kno-01t" "A" "spec" "a")
            b (create-doc tmp "kno-01t" "B" "spec" "b")
            a-bytes (slurp a)]
        (cli/document-rm-cmd (ctx tmp) {:id (doc-id b)})
        (is (not (fs/exists? b)))
        (is (= a-bytes (slurp a)))))))

(deftest document-body-input-layers-test
  (testing "a body arrives by argument, by stdin and by injected editor"
    (with-tmp tmp
      (let [body "line one\n\n---\n\n## not ours\n  "]
        (doseq [opts [{:text body}
                      {:stdin-tty? false :stdin-reader-fn (constantly body)}
                      {:stdin-tty? true :editor-fn (constantly body)}]]
          (let [p (cli/document-add-cmd (ctx tmp)
                                        (merge {:ticket "kno-01t" :title "T"
                                                :type "spec"} opts))]
            (is (= body (:body (ticket/parse (slurp p)))))))))))
```
- [ ] **Step 3: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failures naming the four tests.
- [ ] **Step 4: Implement the handlers**
Implement `document-add-cmd`, `document-show-cmd`, `document-put-cmd`, `document-rm-cmd`, `document-ls-cmd` in `cli.clj`. `add` resolves its body through the existing `resolve-note-content` (explicit text, else stdin when not a tty, else editor) and refuses an out-of-list type before writing. `put` resolves the target first and fails `doc_not_found` when it matches nothing, `ambiguous_doc` when it matches more than one, and otherwise replaces title, type and body together while preserving `:created`.
- [ ] **Step 5: Register the command group**
In `help/registry`, add a `:document` parent entry carrying `:subcommands [:document/add :document/show :document/put :document/rm :document/ls]`, plus one entry per subcommand, following the `:dep` / `:dep/tree` pair. Put the replace-is-total and delete-refuses caveats in each command's `:notes`, not in `SKILL.md`. Register no `doc` or `docs` alias.
- [ ] **Step 6: Wire dispatch**
In `main.clj`'s `case`, add `"document" (document-handler rest-argv)` routing the five subcommands, and add the new codes to the error envelope paths.
- [ ] **Step 7: Run, lint, commit**
```bash
bb test 2>&1 | tail -3
clj-kondo --lint src test
git add src/knot/cli.clj src/knot/help.clj src/knot/main.clj test/knot/cli_test.clj test/knot/integration_test.clj test/knot/help_test.clj
dev-gate git commit -m "feat(cli): knot document command group with PUT semantics (R6, R7, R31)"
```
Expected: `0 failures, 0 errors`.

---

### Task 8: Show integration

**Implements:** R19, R20, R21, R29 (third pinned shape), R39, AC-5, AC-16b, AC-17, AC-23, AC-24, AC-25
**Depends on:** Task 5, Task 7   **Parallel with:** none
**Why Task 7:** the AC-24 and AC-25 integration tests build their fixtures with `knot document add` rather than by planting files, so the command group must exist first. Stated so the dependency is not dropped later as dead weight.

**Files:**
- Modify: `src/knot/ticket.clj` — sixth `reserved-section-owners` row
- Modify: `src/knot/output.clj` — `## Documents` render, `documents` JSON array, `docs` in scanned counts
- Modify: `src/knot/cli.clj` — `show-cmd` must load the documents and pass them; `info-data` gains the third pinned shape
- Test: `test/knot/output_test.clj` (render and envelope shape), `test/knot/json_contract_test.clj` (the three pinned shapes, including `info-exposes-doc-types-test`)

**Interfaces:**
- Consumes: `query/documents-for`, `store/load-docs-for`.
- Produces: `show --json` `data.documents` — an array of `{id, title, type}`, present and empty when the ticket owns none, never carrying bodies.

**Guardrails:** Metadata only in the envelope — a body would make the most common read unbounded. Both output modes must name the same documents. On a ticket carrying a legacy heading the derived section is the single authority and the authored text is ordinary body content (R39). **`show` calls `store/load-docs-for`, never `store/load-all-docs`** — the whole-corpus loader sits beside the scoped one with a similar name and pairs more naturally with the filter function, and this task's tests take documents as fixtures, so a wrong call site produces byte-identical output and a green suite. The cost invariant has to be constrained here because nothing downstream can catch it.

- [ ] **Step 1: Write the failing tests**
```clojure
(deftest documents-array-always-present-test
  (testing "the key is present and empty when the ticket owns nothing"
    (let [d (json/parse-string (output/show-json t [] {}) true)]
      (is (contains? (:data d) :documents))
      (is (= [] (get-in d [:data :documents]))))))

(deftest documents-metadata-only-test
  (testing "a long body never reaches the envelope"
    (let [docs [{:frontmatter {:id "kno-d01a" :title "T" :type "spec"}
                 :body (apply str (repeat 10000 "x"))}]
          d    (json/parse-string (output/show-json t docs {}) true)
          entry (first (get-in d [:data :documents]))]
      (is (= #{:id :title :type} (set (keys entry))))
      (is (< (count (output/show-json t docs {})) 2000)))))

(deftest both-modes-agree-test
  (testing "human and JSON name the same documents, legacy heading or not"
    (let [legacy (assoc t :body "## Documents\n\nauthored prose\n")
          docs   [{:frontmatter {:id "kno-d01a" :title "Real" :type "spec"}}]
          text   (output/show-text legacy docs {})
          j      (json/parse-string (output/show-json legacy docs {}) true)]
      (is (re-find #"kno-d01a" text))
      (is (= ["kno-d01a"] (mapv :id (get-in j [:data :documents]))))
      (is (not (re-find #"authored prose" (pr-str (get-in j [:data :documents]))))))))
```
- [ ] **Step 2: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failures naming the three tests.
- [ ] **Step 3: Widen the renderers and update their only call site**
`output/show-json` and `output/show-text` are 2-arity today and are called with two arguments at `src/knot/cli.clj:313-314` (`(output/show-json loaded inverses*)`). The Step 1 tests call them with three. Widen both to take the document seq, and update `show-cmd` to load it — the renderers cannot invent it:
```clojure
;; in cli/show-cmd, alongside the existing `inverses*` binding
docs (store/load-docs-for (:project-root ctx) (:tickets-dir ctx)
                          (get-in loaded [:frontmatter :id]))
...
(if (:json? opts)
  (output/show-json loaded inverses* docs)
  (output/show-text loaded inverses* docs))
```
Widening without updating the call site is a compile error, so this step is not optional and is why `cli.clj` is in this task's Files list.
- [ ] **Step 4: Add the reserved-section row**
In `ticket/reserved-section-owners`, append after `Linked`:
```clojure
   {:heading "Documents" :field "ticket" :writer "knot document add" :inverse? true}
```
The table already carries two `:inverse?` rows reading backwards from other tickets' fields; this is a third, reading from another corpus.
- [ ] **Step 5: Render and serialize**
Add `## Documents` to the derived-section render, listing `title (type) — id` per document, and add `:documents` to the `show` JSON payload as `{id, title, type}` entries with `[]` as the always-present default, alongside the existing `json-vector-default-keys` treatment. Add `:docs` to the check envelope's `:scanned` map.
- [ ] **Step 6: Add the two config keys to `info`'s allowed values — the third pinned shape**
`info-data` in `src/knot/cli.clj` exposes `allowed_values` carrying `:statuses`, `:types` and `:modes`. The two new config keys belong there by the same pattern, and R29 requires every destination the existing keys reach. Add a failing test first:
```clojure
(deftest info-exposes-doc-types-test
  (testing "the third pinned shape carries the new keys by the existing pattern"
    (let [d (json/parse-string (cli/info-cmd (ctx tmp) {:json? true}) true)
          av (get-in d [:data :allowed_values])]
      (is (= ["spec" "plan" "other"] (:doc_types av)))
      (is (= "other" (get-in d [:data :defaults :default_doc_type]))))))
```
then extend `info-data` to emit `:doc_types` alongside `:types` and `:modes`, and `:default_doc_type` alongside the other defaults.
- [ ] **Step 7: Run, lint, regenerate schema, commit**
```bash
bb test 2>&1 | tail -3
clj-kondo --lint src test
bb gen:schema
git add src/knot/ticket.clj src/knot/output.clj src/knot/cli.clj knot.schema.json test/knot/output_test.clj test/knot/json_contract_test.clj
dev-gate git commit -m "feat(output): render and serialize a ticket's documents (R19, R20, R39)"
```
Expected: `0 failures, 0 errors`; `bb gen:schema` prints `Wrote <path>/knot.schema.json`.

---

### Task 9: Delete integration and the successor ADR

**Implements:** R17, R36, AC-19, AC-20
**Depends on:** Task 4, Task 7   **Parallel with:** none

**Files:**
- Modify: `src/knot/cli.clj` — `delete-cmd`
- Create: `docs/adr/0022-cascade-removes-destroyed-dependents-last.md`
- Modify: `docs/adr/0008-delete-defaults-to-leaf-only.md` — inline marker
- Test: `test/knot/cli_test.clj` (already serial; 14 markers)

**Interfaces:**
- Consumes: `store/load-docs-for`, `store/delete-doc!`.
- Produces: `has_incoming_refs` gains a `documents` payload listing owned document ids.

**Guardrails:** Under cascade, documents are removed **after** the ticket. The reverse order destroys documents beneath a still-live ticket if the run is interrupted — unrecoverable and undetectable, because R2 stores nothing on the ticket, so a ticket that lost documents is byte-identical to one that never had any. **The fault-injection assertion must be in the diff** — `quality-engineer` holds AC-20 unaccepted without it.

**Rollback:** `.tickets/` is git-tracked; `git checkout -- .tickets/` restores any file removed during a failed cascade. Capture the pre-change state with `git status --porcelain .tickets/` before running a cascade by hand.

- [ ] **Step 1: Write the failing tests**
```clojure
(deftest delete-refuses-while-documents-exist-test
  (testing "a bare delete refuses and enumerates the owned documents"
    (with-tmp tmp
      (let [_ (create-doc tmp "kno-01t" "A" "spec" "a")
            r (cli/delete-cmd (ctx tmp) {:id "kno-01t" :json? true})
            e (:error (json/parse-string r true))]
        (is (= "has_incoming_refs" (:code e)))
        (is (= 1 (count (:documents e))))))))

(deftest cascade-removes-documents-after-the-ticket-test
  (testing "an interrupted cascade leaves reportable orphans, never documents under a live ticket"
    (with-tmp tmp
      (let [_          (create-doc tmp "kno-01t" "A" "spec" "a")
            calls      (atom 0)]
        (try
          (with-redefs [store/delete-doc!
                        (fn [& args]
                          (swap! calls inc)
                          (throw (ex-info "simulated document removal failure"
                                          {:kind ::simulated})))]
            (cli/delete-cmd (ctx tmp) {:id "kno-01t" :cascade? true})
            (is false "delete-cmd should re-throw on document removal failure"))
          (catch clojure.lang.ExceptionInfo e
            (is (= ::simulated (:kind (ex-data e))))))
        (is (nil? (store/find-existing-path tmp ".tickets" "kno-01t"))
            "the ticket is already gone -- documents are removed last")
        (is (= 1 @calls)
            "the document-removal path was entered exactly once, so ::simulated came from there")
        (is (seq (store/load-all-docs tmp ".tickets"))
            "the documents survive the interrupted cascade")
        ;; AC-20's second half. Reportability is the whole justification in
        ;; ADR 0022 -- documents-last is acceptable BECAUSE the intermediate
        ;; state is reportable -- so assert the thing the ADR rests on.
        (let [issues (:issues (check/run (ctx tmp)))]
          (is (some #(= :doc_unknown_ticket (:code %)) issues)
              "the orphaned documents are reported under the orphan code"))))))
```
- [ ] **Step 2: Run to verify failure**
Run: `bb test 2>&1 | tail -3`
Expected: failures naming both tests.
- [ ] **Step 3: Implement the refusal and the ordering**
In `delete-cmd`, load the target's documents alongside `incoming-refs`. Refuse without `--cascade` when either is non-empty, adding a `:documents` vector to the `has_incoming_refs` payload. Under `--cascade`, keep the existing referrers-first ordering, then unlink the ticket, and only then remove the documents.
- [ ] **Step 4: Write the successor ADR**
Create `docs/adr/0022-cascade-removes-destroyed-dependents-last.md` stating the rule at principle level — *remove last whatever leaves a reportable intermediate state; a dependent side that is rewritten goes first, a dependent side that is destroyed goes last* — with the R2 entailment that makes the asymmetry forced rather than chosen, and the reciprocal "a future change to either is a contract change to both" line. Follow the shape ADR 0016 used to succeed ADR 0015.
- [ ] **Step 5: Mark the superseded clause in ADR 0008**
Add an inline marker to ADR 0008's write-ordering and abort-before-unlinking clause pointing at 0022. Do **not** mark the ADR superseded as a whole and do **not** touch its success contract — that contract is scoped to a *successful* delete and survives intact.
- [ ] **Step 6: Run, lint, commit**
```bash
bb test 2>&1 | tail -3
clj-kondo --lint src test
git add src/knot/cli.clj docs/adr/0022-cascade-removes-destroyed-dependents-last.md docs/adr/0008-delete-defaults-to-leaf-only.md test/knot/cli_test.clj
dev-gate git commit -m "feat(cli): delete refuses on owned documents and cascades them last (R17)"
```
Expected: `0 failures, 0 errors`.

---

### Task 10: Context surfaces, catalogues and the guard self-test

**Implements:** R26, R27, R28, R29, R34, AC-14c
**Depends on:** Task 6, Task 7, Task 8, Task 9   **Parallel with:** none

**Files:**
- Modify: `resources/knot/skill/references/json.md` — error-code and check-code rows
- Modify: `README.md` — two `.knot.edn` rows
- Modify: `src/knot/cli.clj` — `knot init` config stub
- Modify: `test/knot/doc_codes_test.clj` — extractor sources plus the self-test
- Regenerate: `.claude/skills/knot/` via `bb knot skill install` (from source, so the stamp matches `version.clj`)

**Interfaces:**
- Consumes: every code emitted by Tasks 6, 7 and 9.
- Produces: a catalogue that `doc_codes_test` verifies in both directions, and a guard that is itself tested.

**Guardrails:** `doc_codes_test` reads **only** `main.clj` and `check.clj` by literal regex. A code emitted anywhere else is invisible and the catalogue goes silently stale; a documented code emitted nowhere breaks the build in the reverse direction. `doc_flags_test` rejects any documented flag its command does not accept. The three ADR-0017 surfaces are pull, push and pointer — README is a destination for the config rows, not a surface.

- [ ] **Step 1: Give the catalogue readers a text-taking arity**
`documented-error-codes` and `documented-check-codes` are zero-arity today and slurp `doc-path` internally (`test/knot/doc_codes_test.clj:47-51`), so nothing can drive them. Add a 1-arity that takes the document text, keeping the 0-arity delegating to it:
```clojure
(defn- documented-check-codes
  ([] (documented-check-codes (slurp doc-path)))
  ([doc] (codes table-row-re (doc-section doc "### `check` shape" "## Example"))))
```
- [ ] **Step 2: Write the guard self-test so it drives the REAL guard**
The self-test must fail when the *guard* is broken, not when `remove` is broken. Re-implementing the guard's comparison against two literals would certify the helper and still pass if the real assertion were weakened or no-opped — which is the exact hole AC-14c exists to close. Drive the real deftest var under redefined sources:
```clojure
(deftest catalogue-guard-fails-on-an-undocumented-code-test
  (testing "the guard itself goes red when an emitted code has no catalogue row"
    (with-redefs [emitted-check-codes   (constantly (sorted-set "invented_doc_code"))
                  documented-check-codes (constantly (sorted-set "dep_cycle"))]
      (let [r (clojure.test/run-test-var #'check-code-catalogue-matches-source-test)]
        (is (pos? (+ (:fail r) (:error r)))
            "the catalogue guard must fail when a code is emitted but undocumented")))))
```
**Consequence to accept deliberately:** this adds a `with-redefs` marker to `doc_codes_test`, moving that namespace from the parallel phase into the serial one. It is a small namespace and the cost is near zero, but it is a real change to the runner profile and is made knowingly rather than by accident.
- [ ] **Step 3: Run the self-test and verify it FAILS**
Run: `bb test 2>&1 | grep -A3 'catalogue-guard-fails'`
Expected: a failure. Task 10 is otherwise the only task with no red step, and it is the one carrying the guard self-test — without this, a vacuously-true self-test passes the final `bb test` exactly as a real one does. Observe it red before making it green.
- [ ] **Step 4: Extend the extractor's sources**
State the invariant rather than widening the extractor speculatively. `cli.clj` emits **zero** `:code "..."` literals today — verified — because it throws `ex-info` that `main.clj` translates into the envelope. So the rule for Task 7 is: **error codes are emitted as literals in `main.clj`**, which the extractor already reads, and check codes as literals in `check.clj`, which it also already reads. No source-list change is needed *if that idiom is followed*. The named fallback: if any new error code ends up emitted as a literal in `cli.clj` instead, add `cli.clj` to `emitted-error-codes`' source list in the same commit, or the catalogue goes silently stale. All seven new codes are lowercase snake_case, so the `[a-z_]+` class needs no widening.
- [ ] **Step 5: Add the catalogue rows**
Add rows to the error table for `doc_not_found`, `ambiguous_doc` **and `invalid_doc_type`**, and to the check table for `invalid_doc_type`, `doc_unknown_ticket`, `doc_directory_mismatch`, `duplicate_doc_id`, `legacy_documents_section`. `invalid_doc_type` is emitted in **both** roles — as a check code by Task 6 and as a write-refusal error code by Task 7 — so it needs a row in each table; catalogued in only one, the other direction goes stale. Extend the `Commands` column of `not_found`, `ambiguous_id` and `has_incoming_refs` to name the document subcommands.
Also update **ADR 0016's conditionality list**. It documents *which* check codes carry `data.issues[].path` and instructs consumers to branch on its presence; three of the new check codes carry that field, so the documented list is falsified the moment they ship. This is one documentation line, not the centralization the earlier flag pointed at — no task adds a new path-bearing *field*, so the six-field trigger does not move, and the new issues inherit `fs/unixify` from the existing normalizer at `cli.clj:1425`.
- [ ] **Step 6: Add the config rows and the init stub**
Add `:doc-types` and `:default-doc-type` rows to the README `.knot.edn` table with their defaults, and to the `knot init` stub with inline comments matching the existing keys' style.
- [ ] **Step 7: Regenerate the installed skill copy FROM SOURCE**
```bash
bb knot skill install
```
Expected: `SKILL.md` carries `<!-- installed by knot 0.14.0 -->`, matching `src/knot/version.clj`.
**Use the bb task, never a bare `knot skill install`.** Verified state: the `knot` on PATH is **0.12.0**, `src/knot/version.clj` is **0.14.0**, and the committed copy is stamped **0.13.0** — the repo is already one version stale from a release that bumped the version without regenerating. A bare `knot skill install` would stamp 0.12.0, moving it *further* from source. Both skill guards are blind to this: `skill_copy_test` compares stamp-stripped, and the stamp test matches only the shape `\d+\.\d+\.\d+`, never the value. But `check.clj`'s `skill-staleness` compares the stamp against the running CLI version, so a source-run `knot check` would newly flag the copy this step just wrote while `bb test` stayed green throughout. Check the stamp value by eye; nothing else will.
- [ ] **Step 8: Run the full gate**
```bash
bb test 2>&1 | tail -3
clj-kondo --lint src test
```
Expected: `0 failures, 0 errors`; `errors: 0, warnings: 0`. `doc_flags_test` and `doc_codes_test` both green.
- [ ] **Step 9: Commit**
```bash
git add resources/knot/skill README.md src/knot/cli.clj test/knot/doc_codes_test.clj .claude/skills/knot
dev-gate git commit -m "docs(surfaces): document the document surface on pull and pointer (R26, R27, R34)"
```
