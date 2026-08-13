---
id: kno-01kzxp508wej
title: JSON path fields ship absolute paths while the docs show them repo-relative
status: closed
type: bug
priority: 3
mode: afk
created: '2026-08-13T13:49:24.636593922Z'
updated: '2026-08-13T14:49:55.722286268Z'
closed: '2026-08-13T14:49:55.722286268Z'
tags:
- cli
- json
acceptance:
- title: check --json issue paths flow through fs/unixify (the only code change)
  done: true
- title: json-protocol.md has a Path fields section stating the absolute rule, and every path example and table row matches the shipped values
  done: true
- title: json_contract_test.clj pins fs/absolute? at all four path sites, and pins tickets_dir as relative
  done: true
- title: 'CHANGELOG carries a fix: entry naming the Windows separator change; schema_version is not bumped'
  done: true
- title: ADR 0016 and a CONTEXT.md sentence record the id-vs-path rule
  done: true
links:
- kno-01kzxmazps09
---

## Description

The `--json` envelope carries filesystem paths at four sites. Every one
ships **absolute** — built from `project-root` (itself absolute), with
`fs/unixify` normalizing only the separator, never the form.

`.claude/skills/knot/references/json-protocol.md` shows them
repo-relative in three worked examples. **Resolved: the docs drifted,
not the code.** The runtime form is correct and stays; the docs move to
meet it. See ## Design for the principle and the full change list.

Observed on v0.9.0:

```
$ knot close <id> --summary s --json | jq -r .meta.archived_to
/abs/path/to/project/.tickets/archive/tst-01abc--a.md
```

### The four sites

| Site | Shipped | Documented |
|------|---------|------------|
| `meta.archived_to` (close / terminal `status` / terminal `update --status`) | absolute, unixified | repo-relative at lines 24, 71, 435 |
| `delete --json` → `data.deleted.path` | absolute, unixified | line 276, form unstated |
| `info --json` → `paths.*` (6 keys) | absolute, unixified — **except** `tickets_dir`, which is the config value (`.tickets`) and is legitimately relative | line 284 placeholders; line 295 states separator only |
| `check --json` → `issues[].path` | absolute, **not** unixified (native separators — missed by the kno-01kqcvp72htb Windows sweep) | **undocumented** |

`issues[].path` is emitted on exactly three codes: `terminal_outside_archive`,
`frontmatter_parse_error`, and `missing_required_field` *only when the
missing field is `:id`*. Absent from `dep_cycle`, `unknown_id`, and the
enum validators. `frontmatter_parse_error` also interpolates the path
into `message` (human-readable prose, not contract-pinned).

Nothing catches the drift: the contract test asserts
`(str/includes? archived-to ".tickets/archive/")`, true of both forms.
`info` is the exception — `cli_test.clj:5031-5037` asserts equality
against `(fs/unixify tmp)`, which does discriminate.

## Design

Settled in a grilling session. The design is closed — this is an
implementation ticket.

### The principle

Knot addresses tickets by **id** and locates files by **path**, and
never uses one for the other job. The id is the portable identifier:
stable across retitles, closes, machines and clones, and the only thing
any knot command accepts — no command takes a path argument. A path is
a machine-local **locator**, emitted to be opened now. Therefore every
path in the JSON envelope is **absolute** and POSIX-separated. A
consumer wanting portability stores the id.

Why not repo-relative:

- Project root is discovered by walking up from cwd, so a
  project-root-relative path is not openable from a subdirectory —
  verified: `knot close --json` run from `<root>/sub` reports the
  root-anchored path. Terminal hyperlinking and agent `Read` tools both
  resolve against cwd, so relative breaks silently there.
- Agent tooling generally requires absolute paths; relative would force
  a second `knot info --json` call to join against `project_root`.
- Relativizing `info.paths` degenerates it: `project_root` becomes `.`,
  `tickets_path` becomes a restatement of `tickets_dir`. Those fields
  exist *because* they are absolute.
- Git looks like a counterexample (`git status --porcelain` is
  repo-relative) but is not: git paths are addresses in the git command
  space (`git add <path>`). Knot has no such loop — its address space is
  ids.
- `check` already obeys the principle: file-level issues carry a path,
  graph-level issues carry only ids.

Known boundary, stated in the docs but not worked around: absolute paths
are wrong across a container boundary where the repo is mounted at a
different path than the host sees. Same limitation as every
path-reporting tool.

Rejected: emitting both (`path` + `relative_path`). Additive and cheap,
but no consumer has asked for the portable form, two spellings of one
fact means half of consumers pick wrong, and it is the speculative
flexibility AGENTS.md section 2 forbids. Adding it later stays
non-breaking, which is itself the argument for not adding it now.

The form is a **contract promise**, not an incidental: stated per field
and pinned by test. The half-promise (documented but unpinned) is what
produced this ticket. Human and JSON surfaces are **locked to one form**
— both already print absolute, so locking is just recording what ships,
and it forecloses a future change to one surface only.

### Change list

**Code — one change.** `fs/unixify` on `:path` in `jsonify-issue`
(`src/knot/cli.clj:1387`), the one straggler from the Windows sweep.
Nothing else moves; no value changes on POSIX.

**Docs — `.claude/skills/knot/references/json-protocol.md`.** Hybrid
structure: state the rule once, restate the form in one clause at each
site, so an agent that greps to a section and stops is not misled (that
is how this drift survived).

- New *Path fields* section: the id-vs-path principle, a table of the
  four sites, the container-boundary caveat.
- Lines 71 and 435 examples → absolute values.
- Line 24 (`meta` row) and line 276 (`delete` row) → gain "absolute".
- Line 284 `paths` block → absolute placeholders, plus the note that
  `tickets_dir` is the config value and stays relative.
- Line ~320 check-issue field clause → add `path`, its three-code
  conditionality, and its form.
- Examples use a neutral absolute root
  (`/home/you/projects/acme/.tickets/archive/kno-01abc--shipped.md`) so
  no reader takes a real path for part of the contract.

Check `SKILL.md` in the same pass (AGENTS.md hard rule) — lines 263 and
270 mention `meta.archived_to` without showing a value, so they likely
need no edit; confirm rather than assume.

**Tests — `test/knot/json_contract_test.clj`.** Assert `(fs/absolute? p)`
at all four sites, keeping `str/includes?` alongside where present: the
two assert different things (form vs. which directory). `fs/absolute?`
is the right discriminator and needs no `os.name` branch — verified
`/tmp/x` true, `.tickets/a.md` false, and JVM Path semantics read a
unixified `C:/...` as absolute on Windows.

- `tickets_dir` must be asserted **relative**, or the next reader
  "fixes" it.
- `info.paths` keeps its `cli_test.clj:5031-5037` equality assertions
  and gains a contract-test mirror with an explicit `fs/absolute?` on
  `project_root`.
- `check.issues[].path` needs a fixture with a real integrity issue
  (e.g. a terminal-status ticket left outside the archive) to have a
  path to assert on.

**Compat — no `schema_version` bump.** The documented bar is
*shape*-incompatible change (renaming `data`, moving `error.code`). A
separator normalization is not a shape change and no POSIX consumer
observes it; documenting an already-emitted field is additive. But the
`check` change is real on Windows (`C:\...` becomes `C:/...`), so it
earns a named `fix:` CHANGELOG entry citing the platform rather than
silence.

**Records.**

- `docs/adr/0016-*.md` — the id-vs-path rule. Successor to ADR 0015:
  0015 recorded *what* `meta.archived_to` means (a location report),
  0016 records *what form* that location takes. Cross-reference it.
- `CONTEXT.md` — one sentence extending line 76 with the
  identifier/locator vocabulary. Glossary only, no implementation
  detail.

### Plan

1. Code + contract tests. Verify: `bb test` green, and inverting one
   `fs/absolute?` assertion to a relative literal makes it fail.
2. Doc pass. Verify: grep `json-protocol.md` and `SKILL.md` for
   `".tickets/` returns no path *values*, only directory references.
3. ADR 0016 + CONTEXT.md sentence. Verify: ADR 0015 cross-references
   resolve.
4. `clj-kondo --lint src test`, `bb test`, commit.

## Notes

**2026-08-13T14:49:55.722286268Z**

JSON path fields are now a stated contract: absolute and POSIX-separated at all four sites, with info's paths.tickets_dir the one deliberate exception (it echoes the config value, not a locator). The docs moved to meet the code, not the reverse — knot addresses tickets by id and only ever locates files by path, and a root-relative path is not openable from a subdirectory since the root is discovered by walking up from cwd. One code change: fs/unixify on :path in jsonify-issue, the last --json field still emitting native separators (C:\... -> C:/... on Windows; no POSIX value changes). json_contract_test now pins fs/absolute? at all four sites and tickets_dir as relative, closing the half-promise -- the old str/includes? assertion was true of both forms, so nothing objected to the drift. ADR 0016 records the id-vs-path rule and succeeds ADR 0015. schema_version stays at 1. Shipped in 50b6966.
