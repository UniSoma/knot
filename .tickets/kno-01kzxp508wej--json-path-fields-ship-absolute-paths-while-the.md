---
id: kno-01kzxp508wej
title: JSON path fields ship absolute paths while the docs show them repo-relative
status: open
type: bug
priority: 3
mode: hitl
created: '2026-08-13T13:49:24.636593922Z'
updated: '2026-08-13T13:49:28.551566514Z'
tags:
- cli
- json
acceptance:
- title: Each JSON path field is deliberately assigned a form (relative or absolute) and the docs state it
  done: false
- title: references/json-protocol.md line 24 and the line 71 example match the shipped values
  done: false
- title: A test discriminates the two forms rather than passing on both
  done: false
- title: The compat decision on schema_version is recorded
  done: false
links:
- kno-01kzxmazps09
---

## Description

The `--json` envelope carries filesystem paths in several places. Every
one of them is emitted **absolute**, because the values are built from
`project-root` (itself absolute) and `fs/unixify` only normalizes the
separator — it does not relativize.

`.claude/skills/knot/references/json-protocol.md` documents them as
repo-relative. Line 24 and the worked example at line 71 both show
`".tickets/archive/kno-01abc--shipped.md"`.

Observed on v0.9.0, run from the project root:

```
$ knot close <id> --summary s --json | jq -r .meta.archived_to
/abs/path/to/project/.tickets/archive/tst-01abc--a.md
```

Nothing catches the drift: the contract test asserts
`(str/includes? archived-to ".tickets/archive/")`, which passes for
both forms.

Known or suspected sites: `meta.archived_to` (close, terminal `status`,
and — after kno-01kzxmazps09 — terminal `update --status`);
`delete --json`'s `data.deleted.path`; the six `paths` values in
`info --json` (cwd, project_root, config_path, tickets_dir,
tickets_path, archive_path). The `info` fields are plausibly
*deliberately* absolute — an absolute `project_root` is the useful
form — so this is not a blanket relativize.

## Design

Two axes to settle, which is why this is `hitl`:

1. **Which form wins per field.** Repo-relative is the portable one a
   consumer can store; absolute is what `info.project_root` exists to
   provide. The answer is probably per-field, not global.
2. **Compat.** Any consumer already parsing the absolute form breaks
   when it goes relative. If the value changes shape, does that clear
   the `schema_version` bar? The documented rule bumps only on
   *shape-incompatible* breaks (renaming `data`, moving `error.code`);
   a same-typed string changing its content convention sits right on
   the line.

Whichever way each field lands, `references/json-protocol.md` needs the
matching edit and the contract test needs an assertion that actually
discriminates — `str/includes?` cannot.
