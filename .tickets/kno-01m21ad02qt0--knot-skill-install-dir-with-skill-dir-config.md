---
id: kno-01m21ad02qt0
title: knot skill install [dir] with :skill-dir config
status: open
type: feature
priority: 2
mode: afk
created: '2026-09-08T20:12:25.029603890Z'
updated: '2026-09-08T20:12:25.029603890Z'
parent: kno-01m21abe2vqs
tags:
- cli
- agents
acceptance:
- title: knot skill install with no args writes to <root>/.claude/skills/knot (or :skill-dir when set) and the result is byte-identical to resources/knot/skill/ apart from the version comment
  done: false
- title: knot skill install <dir> writes to <dir>, overwrites existing files, and prints a stderr hint when <dir> differs from the effective :skill-dir
  done: false
- title: --json returns {dir, files}; knot info shows :skill-dir; an unknown-key warning is not emitted for :skill-dir
  done: false
- title: Help registry entry with :notes exists; init help mentions knot skill install; README install section uses the command instead of cp -r
  done: false
deps:
- kno-01m21aczttph
---

## Description

New command `knot skill install [dir]`. Writes `resources/knot/skill/` out as the agent skill: SKILL.md, references/, agents/openai.yaml. `dir` is the directory that will contain SKILL.md. Default: `.knot.edn` `:skill-dir` when set (relative paths resolve from the project root, `~` expands), else `<project-root>/.claude/skills/knot`. An explicit `dir` always wins. Always overwrites the whole directory (knot owns these files); prints the files written; `--json` returns them as a list under `.data.files` plus `.data.dir`.

Insert an HTML comment right after the SKILL.md frontmatter: `<!-- installed by knot <version> -->` (a comment, not a frontmatter key, so no harness rejects it). The committed copy in this repo carries the same comment so the identity test from the sibling ticket still holds — regenerate it with this command.

`:skill-dir` becomes a recognized `.knot.edn` key: shown by `knot info`, read by `prime` (sibling ticket) and by this command. Install never writes `.knot.edn`; when an explicit `dir` differs from the effective `:skill-dir`, print a one-line stderr hint to add `:skill-dir` so `knot prime` can find it.

Register the command in the help registry with `:notes` for the overwrite semantics; add a `:notes` line to `init` mentioning `knot skill install`. README's `cp -r` install block becomes this command. Prior art: beads `bd setup <agent>`.
