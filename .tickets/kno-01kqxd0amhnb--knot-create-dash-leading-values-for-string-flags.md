---
id: kno-01kqxd0amhnb
title: 'knot create: dash-leading values for string flags fail with cryptic error'
status: in_progress
type: bug
priority: 3
mode: hitl
created: '2026-05-06T01:03:59.633375082Z'
updated: '2026-05-07T01:39:25.606713011Z'
tags:
- refine
- v0.4
acceptance:
- title: Root cause documented (parser, rule, line).
  done: true
- title: 'Failure surface mapped: list of (command, flag) pairs that reject dash-leading values.'
  done: true
- title: Verification of whether --<flag>=<value> form is a working escape.
  done: true
- title: Decision recorded between (a) document, (b) pre-process argv, (c) upstream patch — with rationale.
  done: true
- title: 'If decision is (b) or (c): a follow-up implementation ticket is filed.'
  done: true
- title: Error message replaced with something useful (suggests --<flag>=<value> form).
  done: true
links:
- kno-01kqn0mtsvpq
- kno-01kqxchq706w
- kno-01kqys6tvsdr
- kno-01kr0129m0y9
---

## Description

Values passed to `knot create`'s string-valued flags (`--acceptance`, presumably also `--description`, `--design`, `--tags`, `--external-ref`) fail when the value starts with `-` followed by another character. The error message is cryptic and lossy.

Reproductions (single-arg shell quoting throughout):

```bash
knot create "x" --acceptance "- text"   # → knot: Unknown option: :
knot create "x" --acceptance "-text"    # → knot: Unknown option: :e
knot create "x" --acceptance "--text"   # → knot: Unknown option: :test
```

Working cases (for contrast):

```bash
knot create "x" --acceptance "-"          # → OK; stored as "-"
knot create "x" --acceptance "test - x"   # → OK; mid-string dash fine
knot create "x" --acceptance "first" \
                --acceptance "second"     # → OK; repeatable form works
```

Hypothesis (to verify): `babashka.cli` treats any argv element starting with `-` as a flag token regardless of whether it sits in a value position, so `--acceptance "- text"` is parsed as flag `--acceptance` followed by a flag-like `- text`, leaving `--acceptance` empty and `- text` as a stray flag. The error message strips the leading `-` and reports the rest as the unknown option, which is why `--text` echoes as `:test` and `-text` echoes as `:e` (only the alias-shaped first char survives).

Practical impact:

- AC titles that legitimately start with `-` (e.g., naming a flag like `--add-tag`) cannot be passed via `knot create --acceptance`.
- Description/design bodies that start with a Markdown bullet (`- item`) cannot be passed via `--description` / `--design`.
- The error tells the user almost nothing — most users will not connect `Unknown option: :e` to "your value started with a dash."

Affected:

- `src/knot/help.clj` — `:create` flag spec (lines ~177-190)
- `src/knot/main.clj` / wherever `babashka.cli/parse-opts` is invoked
- Likely upstream: `babashka.cli` parsing rules

Surfaced while drafting `kno-01kqxchq706w`. Workaround: rephrase value to not start with `-`, or set the field via `knot update` after create (note: `knot update --acceptance` is itself broken — silently absorbed; that aspect is tracked by `kno-01kqn0mtsvpq`).

## Design

Investigation ticket — root cause not yet pinned. Branches:

1. **Map the failure surface.** Run the dash-leading repro across every string-valued flag on every command; record which fail and which work. Confirms whether this is `babashka.cli`-wide or specific to `:coerce []` flags.
2. **Locate root cause.** Read `babashka.cli`'s argv tokenization (likely in `babashka.cli/parse-args`); check for an existing escape mechanism (e.g., `--`, `=` form like `--acceptance=- text`).
3. **Pick a fix.** Three candidates:
   - **(a) Document the limitation** in `--<flag>` help text and `knot create --help` examples. Smallest change. Punts the workaround onto users.
   - **(b) Pre-process argv** in knot's main entry: detect value-position dash-leading args and quote them via the parser's escape mechanism (if one exists). Local fix, doesn't depend on upstream.
   - **(c) File upstream issue / patch** with `babashka.cli`. Slowest but cleanest.

Decision criterion: if `babashka.cli` already supports `--<flag>=<value>` form (no shell-token boundary), (a) is sufficient — just document it. If not, (b) or (c) become more attractive.

## Notes

**2026-05-06T13:56:36.725464523Z**

External user reproed the dash-leading parse bug with a multi-line value: `--acceptance "- one\n- two\n- three"` errors with `Unknown option: :`. Same root cause as the single-line dash-leading repros already documented; logging here so the failure-surface map captures the multi-line shape too. See kno-01kqys6tvsdr for the related (but distinct) numeric-missing-value variant from the same report.

**2026-05-06T23:55:03.377523382Z**

**2026-05-07T01:39:25.606713011Z**

## Implementation landed (AC #6)

- `dash-leading-value-mishap?` helper in src/knot/main.clj inspects babashka.cli's ex-data: matches when `:type :org.babashka/cli` + `:cause :restrict` AND a flag in `:spec` (with `:coerce` ≠ `:boolean`) appears in `:opts` with implicit `true` / `[true]`. That is the smoking-gun signature: a value-bearing flag collapsed to implicit-true because the parser reparsed its value as a flag.
- `-main`'s catch branches on the helper and prints the actionable hint instead of the bare `Unknown option: …` message. Genuine unknown-flag errors (no spec match) keep the original message.
- New test: `dash-leading-value-error-hint-test` in test/knot/integration_test.clj — 9 assertions covering create --acceptance, update --add-tag, and the genuine-unknown-flag negative case. Full suite: 332 tests / 4259 assertions, 0 failures. Lint: clean for new code (residual warnings all pre-existing).

## Follow-up

- kno-01kr0129m0y9 tracks the implementation work to extend the pre-extract pattern across all value-bearing string flags. Once that lands, AC #5 of that ticket flags this hint's text for review/trim.

## Root cause

`babashka.cli/parse-key` (cli.cljc:344-367) classifies any argv element starting with `-` as an option token (long-opt or short-opt group), regardless of whether it sits in a value position. The relevant predicate:

```clojure
hyphen-opt? (and (not= :keywords mode)
                 (= \- fst-char)
                 (> (count arg) 1)
                 (let [k (keyword (subs arg 1))]
                   (or (contains? known-keys k)
                       (contains? alias-keys k)
                       (not (number-char? snd-char)))))
```

In the value-consumption path (cli.cljc:~420), the parser calls `parse-key` on the next argv element. If `:hyphen-opt` is true, the current flag is set to implicit `true` and the next token is reparsed as flags — so `--acceptance "- text"` becomes `:acceptance true` plus a short-opt group ` `,`t`,`e`,`x` (the leading `-` strips to give a single-char alias each).

The single-char `-` works only because the `(> (count arg) 1)` guard excludes it from `hyphen-opt?` classification.

## Failure surface

Bug is parser-wide, not flag-specific. Every dash-leading argv token is misparsed. Categories:

**A. Loud failure (`Unknown option: …`, exit 1) — string-valued flag values:**
- `knot create`: `--type`, `--assignee`, `--external-ref`, `--parent`, `--tags`, `--mode`, `--acceptance`, `--dep`, `--link`
- `knot update`: `--title`, `--type`, `--mode`, `--assignee`, `--parent`, `--tags`, `--add-tag`, `--remove-tag`, `--external-ref`, `--ac`, `--add-ac`, `--remove-ac`
- `knot status` / `knot close`: `--summary`
- `knot list` / `ready` / `blocked` / `closed`: `--status`, `--assignee`, `--tag`, `--type`, `--mode`
- `knot init`: `--prefix`, `--tickets-dir`
- `knot check`: `--severity`, `--code`

**B. Loud failure — positional args that happen to start with `-`:**
- `knot status <id> <new-status>` (e.g. `-cancelled`)
- `knot dep <from> <to>`, `knot undep`, `knot link`, `knot unlink`
- `knot add-note <id> <text…>` (variadic)
- `knot check <id…>` (variadic)

**C. Silent acceptance (worse than loud failure):**
- `knot prime --status -open` runs without complaint; the filter is dropped silently.

**D. Already immune:**
- `knot create / update --description / --design / --body` — pre-extracted by `extract-body-flags` in src/knot/main.clj:82-112 before babashka.cli sees them. Confirmed working with dash-leading values.

## Verification of `--<flag>=<value>` escape

Empirically NOT a working escape. babashka.cli runs the full hyphen-opt classification on the post-`=` value:

| Input (single argv)          | Parsed `:opts` |
| --- | --- |
| `--acceptance=- text`        | `{:  true, :t true, :e true, :x true}`  ← `:acceptance` lost |
| `--acceptance=-text`         | `{:t true, :e true, :x true}` ← `:acceptance` lost |
| `--acceptance=--text`        | `{:acceptance [true], :text true}` |
| `--acceptance=-`             | `{:acceptance [-]}` ← only single `-` survives |

The only known workaround is to prefix the value with whitespace (`--acceptance " - text"`) — the leading space disqualifies `parse-key`'s hyphen-opt branch. Ugly: the space is preserved in the stored value.

The `--` end-of-options separator works to halt flag parsing but consumes everything after as positional args, so it can't deliver `--flag value` semantics.

## Decision: option (b) — extend the pre-extract pattern + add a useful error message

Rationale:
- (a) Document only: rejected. Error is unactionable; `=` form does not help; some commands silently swallow (`prime`).
- (b) Pre-process argv: chosen. Pattern is already established for body flags (`extract-body-flags`, src/knot/main.clj:82-112) — the comment at L52-56 explicitly explains it as the dash-leading workaround. Generalising to all string-valued flags is mechanical and can be driven from `knot.help/registry` so it stays in sync as flags evolve.
- (c) Upstream patch: rejected for now. Behavioural change to babashka.cli's hyphen-opt classification would be breaking; getting it accepted is high-cost / slow. Worth a courtesy issue but not the fix path for v0.4.

Prototype confirmed at REPL: `pre-extract ["x" "--acceptance" "- a" "--acceptance" "- b" "--type" "bug"] #{"--acceptance"}` correctly returns `{:acceptance ["- a" "- b"]}` and a clean residual argv `[x --type bug]` for babashka.cli.

Notes / boundaries on (b):
- Covers categories A and C. Does NOT fix B (positional args) — pre-extract requires a flag token to anchor on. Positionals starting with `-` remain broken; mitigated by recommending users quote-prefix or use `=` for adjacent flags.
- Pre-extract must understand both `--flag value` and `--flag=value` shapes, plus aliases (`-d` for `--description`, `-t` for `--type`, etc.) — already handled by the existing body-flag extractor; replicate the shape.
- Repeatable (`:coerce []`) flags accumulate; non-repeatable take last.

## Error-message improvement (AC #6)

`-main`'s catch (src/knot/main.clj:976-979) prints `(.getMessage e)` raw, so babashka.cli's `Unknown option: :e` reaches the user verbatim. babashka.cli's ex-data shape is rich:

```
{:type :org.babashka/cli, :cause :restrict, :option :e,
 :opts {:acceptance [true], :t true, :e true, :x true}, ...}
```

Detection heuristic: `:type :org.babashka/cli`, `:cause :restrict`, AND `:option`'s string form is suspicious (single char, contains a space, or is empty). When matched, replace the message with something like:

```
knot: looks like a value starting with `-` was passed to a flag (got "<original>").
      babashka.cli treats argv tokens starting with `-` as flags, even after `=`.
      Workaround: prefix the value with a space, e.g. --acceptance " - text".
      Tracked: kno-01kqxd0amhnb.
```

(Once the pre-extract follow-up lands and covers the affected flags, the workaround line can shrink — but the detection is still useful for any string flag we missed and for positional args where pre-extract cannot help.)

## Follow-up implementation ticket

To be filed (AC #5): "Pre-extract dash-leading-safe handling for all value-bearing string flags." Scope: derive the per-command extract map from `knot.help/registry` (every flag without `:coerce :boolean`/`:coerce :long` and without `:body? true` for the existing extractor's overlap), wire into `init-handler` / `create-handler` / `update-handler` / list handlers / `status` / `close` / `dep` / `check` / `prime` paths. Add tests with `- text`, `--text`, `-x`, multi-line `"- one\n- two\n- three"` repros across each affected command/flag. Decoupled from the error-message change (AC #6), which is in-scope here.
