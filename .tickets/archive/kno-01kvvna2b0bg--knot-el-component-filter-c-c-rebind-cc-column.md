---
id: kno-01kvvna2b0bg
title: 'knot.el: --component filter (c/C rebind) + CC column'
status: closed
type: task
priority: 3
mode: hitl
created: '2026-06-24T01:53:25.856261089Z'
updated: '2026-06-24T02:06:12.421844743Z'
closed: '2026-06-24T02:06:12.421844743Z'
---

## Description

Mirror the CLI --component filter + CC column (ADR 0013/0014, kno-01kvvf1z9etb/kno-01kvvjwybf58) into emacs/knot.el. Filter: prompt-less single-seed from cursor/single-mark (>1 mark refuses with message), auto-clears closure sibling and vice versa, closed-view c → message+no-op, C-u c clears component-only; rebind transient c→component, C→closure. Column: leading ("CC" 4 t) left-aligned, always-on, null/singletons render -, reuse knot-list--metric-cell with field cc; view-accepts-p returns nil for (closed,component). Docs: emacs/README.md only; no CLI/SKILL/CONTEXT changes.

## Notes

**2026-06-24T01:56:02.191116462Z**

Implemented in emacs/knot.el + emacs/README.md. Filter consts (filter-keys, filter-cli-flags) gained component; view-accepts-p returns nil for (closed,component); new knot-list-filter-set-component (prompt-less single-seed, >1 mark refuses, closed-view message no-op, auto-clears closure, C-u clears); set-closure now auto-clears component; transient c->component / C->closure + docstring. Column: ('CC' 4 t) prepended to tabulated-list-format (left-aligned, leading), CC cell via (knot-list--metric-cell row 'cc) prepended to row vector; metric-cell docstring covers cc null=singleton. bb lint:elisp clean (exit 0). CLI flag + mutual-exclusion verified end-to-end. No Clojure touched. Remaining: manual emacs verification (create a 2-member live component, exercise f c / f C / C-u f c across list/ready/blocked/closed) + commit.

**2026-06-24T02:06:12.421844743Z**

Mirrored CLI --component filter + CC column into knot.el. Filter: prompt-less single-seed (cursor/lone mark; >1 mark refuses), c->component/C->closure rebind with mutual auto-clear, closed-view no-op+message, C-u clears. Column: leading left-aligned ('CC' 4 t), always-on, null/singleton renders -, reuses knot-list--metric-cell with field cc. view-accepts-p prunes component in closed view. emacs/README.md updated. bb lint:elisp + clj-kondo + bb test all clean. No CLI/SKILL/CONTEXT changes.
