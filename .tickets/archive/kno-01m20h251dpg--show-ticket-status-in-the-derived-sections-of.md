---
id: kno-01m20h251dpg
title: Show ticket status in the derived sections of knot show and knot deps
status: closed
type: task
priority: 2
mode: afk
created: '2026-09-08T12:49:35.277654170Z'
updated: '2026-09-08T13:11:30.889749421Z'
closed: '2026-09-08T13:11:30.889749421Z'
acceptance:
- title: knot show renders each Blockers/Blocking/Children/Linked entry as `- <id>  [<status>]  <title>`, using the literal frontmatter status
  done: true
- title: knot deps renders each non-missing node, root included, as `<id>  [<status>]  <title>`, with the seen-before `↑` still trailing
  done: true
- title: A missing referent still renders `[missing]` unchanged, and a resolved ticket with no frontmatter status renders no marker at all, in both surfaces
  done: true
- title: show --json and deps --json output is byte-unchanged, and no ANSI or padding is introduced in either text render
  done: true
- title: output_test.clj covers the status marker, the missing case and the absent-status case for both renders; bb test and clj-kondo --lint src test pass
  done: true
---

## Description

The four sections `knot show` appends — `## Blockers`, `## Blocking`, `## Children`, `## Linked` — render each entry as `- <id>  <title>` (`inverse-line`, `src/knot/output.clj:19-26`). Nothing says what state the referenced ticket is in, so a reader looking at `knot show kno-01m12f94nctd` sees `kno-01kqzh3jgwf0` under Linked with no hint that it closed weeks ago. Agents and humans both have to `show` every referent to find out.

The `deps` text tree has the same gap: `node-label` (`src/knot/output.clj:465-471`) prints `<id>  <title>`, and a closed node there is more misleading still, since the tree is what you read to decide whether you are blocked.

Both JSON surfaces already carry the status — `jsonify-inverse-entry` (`output.clj:230-238`) and `jsonify-tree-node` (`output.clj:500-518`) each emit `{id, title, status}`. The text renders are the only place it is missing, and the entries they render already hold the fully resolved ticket, so no extra I/O is needed.

Prior art: the Emacs extension already annotates its show buffer this way — `- kno-01kqzh3jgwf0 [closed] Release-tag smoke CI workflow`.

## Design

Add a bracketed literal status between the id and the title in both renders:

```
- kno-01kqcpb0t5s7  [open]         Refine distribution model: pinning, channels, native-image
- kno-01kqzh3jgwf0  [closed]       Release-tag smoke CI workflow
kno-01m12f94nctd  [in_progress]  Publish knot binaries ↑
```

Decisions taken, with their reasons:

- **Literal configured status, always.** Not a `status-role` word and not a closed-only marker — the literal string is exactly what `--json` already emits, so the two surfaces agree, and `in_progress` on a blocker is real signal. Absence of a marker never carries meaning.
- **The bracket slot means "state of the thing behind this id".** `[missing]` stays exactly as it is and is not further qualified; it is one answer to that question, sitting alongside `[open]` and `[closed]`.
- **A resolved ticket whose frontmatter has no status renders no marker** — `- <id>  <title>`, as today. These read sites are deliberately forgiving; inventing `[?]` would put a fake value in front of an agent.
- **No padding.** The id column is already fixed-width, so `[` starts at a constant offset. Padding to the widest marker in a render would make output width depend on its contents, so an unrelated ticket flipping to `in_progress` would churn a `show` diff.
- **No color.** `show-text` and `dep-tree-text` take no render context and have never carried ANSI; only `ls-table` and `prime` are colorized. Threading config into two more call sites is a separate change.
- **Both call sites in one commit**, so the CLI does not contradict itself for a release.

In the tree, every non-missing node gets a marker including the root, and the seen-before `↑` stays trailing: `<id>  [open]  <title> ↑`.

Out of scope: JSON shapes (already correct), color, `prime`, `ls`.

Surfaces: `references/json-protocol.md` and the `knot` SKILL need no edit, since no JSON shape moves and no help `:notes` or doc specifies the text line format — it lives only in the two docstrings, which move with the code.

## Notes

**2026-09-08T13:11:30.889749421Z**

Added a bracketed literal status between the id and the title in both text renders: `- <id>  [<status>]  <title>` in the four derived sections of `knot show`, and `<id>  [<status>]  <title>` for every non-missing node of `knot dep tree`, root included, with the seen-before `↑` still trailing.

A single private `status-marker` helper in output.clj reads the literal frontmatter status and yields `""` when there is none, so an unstatused ticket renders exactly as before and no value is invented. `[missing]` short-circuits ahead of it and is unchanged. No padding, no ANSI, no JSON shape moved — both --json surfaces are byte-identical. Collapsing node-label's now-duplicated cond arms into one `if` dropped the repeated label body.

output_test.clj gains show-text-status-marker-test and dep-tree-text-status-marker-test covering the marker, the missing case and the absent-status case on both renders; the assertions in output_test, cli_test and integration_test that pinned the old line format were updated. bb test is green apart from the pre-existing, unrelated missing-numeric-value-error-hint-test failure; clj-kondo is clean.
