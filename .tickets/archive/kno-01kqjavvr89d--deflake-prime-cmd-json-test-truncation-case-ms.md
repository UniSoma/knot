---
id: kno-01kqjavvr89d
title: 'Eliminate same-ms id collisions: monotonic ULID + atomic create-or-retry'
status: closed
type: bug
priority: 0
mode: afk
created: '2026-05-01T17:54:57.416209665Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-05-01T19:35:09.978736855Z'
tags:
- v0.3
- test
- flake
- id-gen
acceptance:
- title: '`knot.ticket/generate-id` is deterministically collision-free for any number of consecutive same-ms calls within a single process (covered by `advance-id-state` unit tests)'
  done: false
- title: '`generate-id` public signature remains single-arity `(generate-id prefix)` — no clock-injection seam exposed'
  done: false
- title: 'Id format unchanged: `<prefix>-<12 lowercase Crockford-base32 chars>`; existing `generate-id-test` regex/length assertions pass without modification'
  done: false
- title: State held in a `defonce` `^:private` atom in `knot.ticket`; no test-only reset hook
  done: false
- title: '`knot.store/save-new!` exists with the documented two-callback signature and retries on filesystem-level id collision'
  done: false
- title: On exhaustion, throws `ex-info` with `{:kind :id-collision-exhausted :attempts <n> :last-id <id>}`; default `max-retries` is 10
  done: false
- title: Atomic write via `Files/write` with `OpenOption[] {CREATE_NEW, WRITE}` — no `fs/exists?`-then-write TOCTOU pattern
  done: false
- title: '`knot.store/save!` overwrite-for-updates semantics unchanged'
  done: false
- title: '`cli/create-cmd` uses `save-new!`; cross-process same-ms create attempts no longer silently overwrite each other'
  done: false
- title: '`create-spaced!` test helper (cli_test.clj:1902–1910) removed; its 4 callsites (lines 2019, 2042, 2058, 2059) use plain `cli/create-cmd`'
  done: false
- title: '`save-direct` helper (cli_test.clj:2361) retained — still load-bearing for `partial-id-resolution-test`'
  done: false
- title: prime-cmd-json-test passes 100 consecutive `bb test` runs without modification to its body
  done: false
- title: No silent sleeps introduced
  done: false
- title: '`bb test` and `clj-kondo --lint src test` both clean'
  done: false
---

## Description

`knot.ticket/generate-id` builds ids as `<prefix>-<10-char ms timestamp><2-char random>` in Crockford base32. Two creates landing in the same ms have a 1/1024 collision probability per pair, and the birthday paradox makes 5+ creates in a tight loop collide noticeably often. When two ids collide, the second `store/save!` call silently overwrites the first file because `save!` is built around `spit` (intentionally — it's the same function used to update existing tickets).

The visible symptom is the "truncation flag and remaining count" sub-test in prime-cmd-json-test (test/knot/cli_test.clj:2333), which creates 5 tickets via `(dotimes [n 5] (cli/create-cmd …))` and intermittently sees only 4 of them on disk. But the bug isn't test-specific: any in-process bulk create (current test suite, future scripted batches) and any cross-process burst (e.g. an LLM dispatching parallel `knot create` shell tools) hits the same race.

Earlier mitigations on the test side (`create-spaced!` with `Thread/sleep 2` at cli_test.clj:1902, `save-direct` bypassing `cli/create-cmd` at cli_test.clj:2361) papered over the bug at the test layer. This ticket fixes it at the id-generation and save layers so the underlying primitive is collision-free. `save-direct` stays — `partial-id-resolution-test` legitimately needs hand-picked ids to construct ambiguity scenarios; only `create-spaced!`, whose sole purpose was dodging the ms-clock race, is removed.

## Fix

Two-layer defense:

**Layer A — Monotonic ULID factory in `knot.ticket/generate-id`.** Standard ULID monotonic spec: hold last `(timestamp, random)` in a `defonce` `^:private` atom. On each call, if `now-ms ≤ last-ts`, reuse `last-ts` and emit `last-rand + 1`; otherwise emit `(now-ms, fresh-random)`. On the (~unreachable) overflow of the 10-bit random space, bump ts by 1 and reset rand to 0. Eliminates intra-process same-ms collisions deterministically without changing the id format.

**Layer B — Atomic create-or-retry in `knot.store/save-new!`.** New function alongside `save!` (which keeps its overwrite-for-updates semantics, untouched). Signature:

```clojure
(save-new! project-root tickets-dir
           gen-id-fn      ; () → id
           build-fn       ; id → {:slug s :ticket {:frontmatter … :body …}}
           {:keys [now terminal-statuses max-retries]
            :or   {max-retries 10}})
```

Per attempt: call `(gen-id-fn)`, call `(build-fn id)`, compute the target path (live vs archive via existing `terminal?` helper), then write atomically with `java.nio.file.Files/write` using `OpenOption[] {CREATE_NEW, WRITE}` — single syscall, atomic create+write, throws `FileAlreadyExistsException` on collision. On collision, regenerate and retry, bounded at `max-retries` (default 10) attempts before throwing `ex-info "id collision retry exhausted" {:kind :id-collision-exhausted :attempts <n> :last-id <id>}`. Same timestamp-stamping and self-heal-prior-paths logic as `save!`. Closes the cross-process gap that Layer A can't see.

(Probed under bb: `Files/write` with `CREATE_NEW` and `FileAlreadyExistsException` both work as expected — the exception class propagates unwrapped.)

`cli/create-cmd` switches from `store/save!` to `store/save-new!`. The `create-spaced!` test helper (lines 1902–1910) and its 4 callsites (lines 2019, 2042, 2058, 2059) revert to plain `cli/create-cmd` since they no longer need the workaround.

## Test plan

- **`knot.ticket-test`** — extract `^:private advance-id-state` (pure fn: `(last-state, now-ms, prefix) → [new-state, id]`) and unit-test the bump algorithm directly via `#'knot.ticket/advance-id-state`: same-ms-bumps-rand, new-ms-fresh-rand, overflow-bumps-ts. The public `generate-id` signature stays single-arity — no clock-injection seam.
- **`knot.store-test`** — new `save-new!` tests, driven by a deterministic `gen-id-fn` backed by `(atom [id1 id2 …])`: happy path (single id, creates file, returns path), collision retry (seq `[taken-id taken-id fresh-id]` with `taken-id` pre-staged on disk → succeeds at `fresh-id`, retry count = 2), exhaustion (seq of all taken ids → throws `:id-collision-exhausted` with `:attempts 10` after the bound is hit).
- **`prime-cmd-json-test`** — line 2333 sub-test left exactly as written; verify it now passes naturally under the fixed `cli/create-cmd`.

## Notes

**2026-05-01T19:35:09.978736855Z**

Two-layer fix for same-ms id collisions, eliminating prime-cmd-json-test truncation flake. Layer A: knot.ticket extracts a pure ^:private advance-id-state (state, now-ms, fresh-rand, prefix → [new-state, id]) implementing the standard ULID monotonic spec — same-ms (or backward-clock) calls reuse last-ts and bump rand by 1, ignoring fresh-rand; rand overflow at 1024 bumps ts by 1 and resets rand to 0; nil/new-ms emits fresh-rand at now-ms. State held in a defonce ^:private atom (survives REPL reload, no test-only reset hook). generate-id stays single-arity; format and 16-char regex unchanged. Tests: advance-id-state-test (5 cases incl. backward-clock), 200-burst monotonic-sortable assertion in generate-id-test. Layer B: knot.store/save-new! with two-callback signature (gen-id-fn, build-fn), atomic write via Files/write CREATE_NEW (no fs/exists?-then-write TOCTOU). Default :max-retries 10; on exhaustion throws ex-info {:kind :id-collision-exhausted :attempts <n> :last-id <id>}. Reuses save!'s stamp-timestamps with prior-status=nil for fresh tickets. save! semantics untouched. Tests: happy path (live + archive routing, blank/non-blank slug, mkdir), collision-retry (counts gen-id-fn calls, asserts pre-existing file untouched), 3-consecutive-collision retry, exhaustion at default 10 + custom 3, atomic-create non-overwrite. Integration: cli/create-cmd switched to save-new! (gen-id closure mints fresh id per attempt, build-fn closure reuses slug+body+opts). create-spaced! helper removed; 4 callsites at cli_test.clj revert to plain cli/create-cmd. save-direct retained for partial-id-resolution-test. Verification: bb test → 210 tests, 1809 assertions, 0 failures; clj-kondo --lint src test → baseline unchanged (3 errors, 5 warnings, all pre-existing on main); prime-cmd-json-test passed 100/100 consecutive bb test runs without modification to its body.
