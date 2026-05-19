---
id: kno-01kryx7ey6m0
title: knot delete <id> — leaf-only path
status: open
type: feature
priority: 1
mode: afk
created: '2026-05-19T01:22:26.886333528Z'
updated: '2026-05-19T01:37:09.044209454Z'
tags:
- needs-triage
acceptance:
- title: Bare `knot delete <id>` removes a leaf ticket from `.tickets/` and prints the removed path
  done: false
- title: Bare `knot delete <id>` removes a leaf ticket from `.tickets/archive/` and prints the removed path
  done: false
- title: Bare `knot delete <id>` refuses (exit 1) when any live ticket references the target, printing each referrer + field
  done: false
- title: Bare `knot delete <id>` refuses (exit 1) when any archived ticket references the target
  done: false
- title: '`--json` on success emits `{ok:true, data:{deleted:{id,path}, cleaned:[]}}`'
  done: false
- title: '`--json` on refusal emits `{ok:false, error:{code:"has_incoming_refs", referrers:[{id,field}]}}`'
  done: false
- title: Not-found id and ambiguous partial id follow the standard error envelopes used by other write commands
  done: false
- title: '`knot help delete` prints the new help block; `.claude/skills/knot/SKILL.md` reflects the new command and is updated in the same commit'
  done: false
- title: ADR 0008 is committed in the same PR; `bb test` and `clj-kondo --lint src test` both clean
  done: false
---

## Description

End-to-end `knot delete <id>` for the safe case: resolves the partial id, scans live + archive for incoming `:parent`/`:deps`/`:links` references, and either deletes the file (stdout = removed path; `--json` returns a success envelope with `data.deleted` and `data.cleaned: []`) or refuses with enumerated referrers (exit 1; under `--json` returns the `has_incoming_refs` error envelope with a `referrers` payload).

Implements the leaf-only behavior pinned by ADR 0008. The `--cascade` opt-in (which actually mutates referrers) is a separate slice that builds on this.

Scope of this slice:
- Argv dispatch for `delete` in main.clj
- `delete-cmd` in cli.clj
- New referrer-scan helper (live + archive, across `:parent`/`:deps`/`:links`)
- File unlink via store (new `store/delete!` or inline via `fs/delete`)
- Stdout = removed path
- `--json` envelopes: success and `has_incoming_refs` refusal
- Standard not-found and ambiguous error handling
- Help registry entry in help.clj
- `.claude/skills/knot/SKILL.md` update (the AGENTS.md hard rule)
- ADR 0008 commit (already drafted at docs/adr/0008-delete-defaults-to-leaf-only.md)
- CHANGELOG entry
