---
id: kno-01ks3gnm9n2k
title: 'emacs: strikethrough terminal deps/links in show top-line'
status: closed
type: feature
priority: 4
mode: afk
created: '2026-05-20T20:19:11.797519517Z'
updated: '2026-05-20T21:41:58.843773678Z'
closed: '2026-05-20T21:41:58.843773678Z'
acceptance:
- title: New `(defface knot-show-terminal-ref ...)` in the `knot` customize group, default `:strike-through t`
  done: true
- title: Top-line `**deps:**` and `**links:**` render with each id propertized individually; commas between ids stay un-struck
  done: true
- title: Each id whose matching entry in `blockers` (for deps) or `linked` (for links) has `status` in `(knot-info-allowed-values 'terminal_statuses)` carries the `knot-show-terminal-ref` face
  done: true
- title: 'Each id whose matching entry has `missing: true` carries the existing `knot-deps-missing` face'
  done: true
- title: Strikethrough face survives `knot-id-buttonize-region` — a buttonized id over a terminal referent stays struck
  done: true
- title: '`knot-show--render-scalar-list` is unchanged and continues to render `**external:**`'
  done: true
- title: Lower `## Blockers` / `## Linked` / `## Children` sections are unchanged — no strikethrough applied
  done: true
- title: '`**parent:**` line is unchanged — no strikethrough applied'
  done: true
---

## Description

The show buffer's top-line currently renders `**deps:** a, b, c` and `**links:** a, b, c` as flat CSV strings via `knot-show--render-scalar-list` (knot.el:1827) — every id displayed identically regardless of whether the referent is still pulling weight. Replace those two call sites with a per-id renderer that consults the envelope's existing `blockers` and `linked` arrays (which already carry `{id, title, status}` or `{id, missing: true}`) and propertizes each id span according to the referent's role: terminal status gets a new `knot-show-terminal-ref` face (strikethrough by default), missing referents reuse the existing `knot-deps-missing` face (italic warning, already used in the deps-tree view).

Scope is the top-line only. The lower `## Blockers` / `## Linked` / `## Children` sections already render `[status]` inline next to each id, so strikethrough there would be redundant. `parent` is out of scope: the envelope carries no parent status (no inverse-section expansion for the parent direction), and a single-value `**parent:** <id>` line is read in one glance — the payoff isn't worth a CLI envelope change or a second subprocess per render. `external_refs` (knot.el:1898) stays on the old `render-scalar-list` path — external URIs to non-knot systems have no knot status to test.

## Design

"Terminal" means any status in `(knot-info-allowed-values 'terminal_statuses)`, not the literal string `"closed"`. Matches `knot-format-status` (knot.el:232–247), which already colors active vs. terminal vs. open roles from the project's `.knot.edn`-configured allowed values, and the `CONTEXT.md` glossary's canonical "terminal status" term. A custom project that uses `won't-do` or `duplicate` as additional terminal statuses gets coherent strikethrough for free.

New face:

```elisp
(defface knot-show-terminal-ref
  '((t :strike-through t))
  "Face for terminal-status ids on the top-line `**deps:**` / `**links:**` rows of `knot-show-mode`."
  :group 'knot)
```

Replace the two scalar-list calls (knot.el:1896–1897) with a new helper, e.g. `knot-show--render-relationship-ids LABEL IDS INVERSE-ENTRIES`, that:

1. Inserts `**LABEL:** ` (no propertization on the markup).
2. Iterates `IDS` and, for each id:
   - Looks up the matching entry in `INVERSE-ENTRIES` (the `blockers` array for `deps`, the `linked` array for `links`) by id equality.
   - Inserts the id with face `knot-show-terminal-ref` when the matched entry's status is in `terminal_statuses`, face `knot-deps-missing` when the matched entry has `missing: true`, no face otherwise.
   - Inserts `, ` glue (un-propertized) between entries, no trailing comma.
3. Inserts a trailing `\n`.

`knot-id-buttonize-region` (knot.el:1931) runs at the end of render and adds button properties. Buttons add their own `face` property; depending on how it's added, it can overwrite ours. Either (a) propertize the id span *after* buttonization, or (b) add the face as a list — Emacs face composition merges list-valued faces. Either is fine; (b) is the smaller diff.

Out of scope:

- `**parent:** <id>` — envelope lacks parent status. If parent strikethrough later matters, the right fix is a new `:parent_expanded {id,title,status}` field on `show --json` (mirroring the existing inverse pattern). Separate ticket.
- `**external:**` — URIs to non-knot systems, no knot status to test.
- Lower `## Blockers` / `## Linked` / `## Children` sections — `[status]` already inline, strikethrough redundant.

## Notes

**2026-05-20T21:41:58.843773678Z**

Closed/terminal deps and links are now struck-through on the **deps:** / **links:** top-line of knot-show-mode; missing referents reuse knot-deps-missing. Implemented via a new knot-show--render-relationship-ids helper that consults the envelope's expanded blockers/linked arrays and applies the face via an evaporating overlay (not a text property) so it survives the font-lock unfontify cycle inherited from markdown-view-mode. New defface knot-show-terminal-ref (:strike-through t). Lower ## Blockers / ## Linked / ## Children sections, **parent:**, and **external:** are unchanged.
