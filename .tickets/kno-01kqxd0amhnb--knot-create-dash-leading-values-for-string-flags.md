---
id: kno-01kqxd0amhnb
title: 'knot create: dash-leading values for string flags fail with cryptic error'
status: open
type: bug
priority: 3
mode: hitl
created: '2026-05-06T01:03:59.633375082Z'
updated: '2026-05-06T01:04:04.290305278Z'
tags:
- refine
acceptance:
- title: Root cause documented (parser, rule, line).
  done: false
- title: 'Failure surface mapped: list of (command, flag) pairs that reject dash-leading values.'
  done: false
- title: Verification of whether --<flag>=<value> form is a working escape.
  done: false
- title: Decision recorded between (a) document, (b) pre-process argv, (c) upstream patch — with rationale.
  done: false
- title: 'If decision is (b) or (c): a follow-up implementation ticket is filed.'
  done: false
- title: Error message replaced with something useful (suggests --<flag>=<value> form).
  done: false
links:
- kno-01kqn0mtsvpq
- kno-01kqxchq706w
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
