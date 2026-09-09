---
id: kno-01m21aczyqmx
title: 'knot help <topic> and knot help topics: bundled concept guides'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-09-08T20:12:24.902552796Z'
updated: '2026-09-09T20:46:13.655716840Z'
closed: '2026-09-09T20:46:13.655716840Z'
parent: kno-01m21abe2vqs
tags:
- cli
- docs
acceptance:
- title: knot help <topic> prints the bundled markdown for each of the six topics and exits 0; knot help topics lists all six with summaries
  done: true
- title: A test asserts no topic name equals any registry command name, alias, or subcommand, and that every resources/knot/skill topic file has a topics entry and vice versa
  done: true
- title: knot help <command> and knot <command> --help behave exactly as before; knot help bogus exits 1 and names knot help topics
  done: true
- title: knot --help top-level output advertises knot help topics in one line
  done: true
deps:
- kno-01m21aczttph
external_refs:
- git:a9c9ee4
---

## Description

Add a `topics` def to `knot.help` mapping topic name → classpath resource + one-line summary, for the six topics shipped by the parent's first child. `knot help <topic>` prints the resource verbatim (no color, no paging); `knot help topics` lists `name  summary`. The `help` dispatcher in `main.clj` resolves commands, aliases and subcommands first and falls back to topics; an unknown word keeps today's `knot help: unknown command` error but mentions `knot help topics`.

Add one line to the top-level `knot --help` under the "Run `knot help <command>`" sentence: concept guides are at `knot help topics`. No inventory of topic names in prose anywhere else (ADR 0017 rule).

Prior art: `git help -g`, `gh help environment`, `jj help -k`.

## Notes

**2026-09-09T20:46:13.655716840Z**

`knot help <topic>` prints any of the six bundled guides and `knot help topics` lists them with summaries. knot.help/topics is an array-map name -> {:resource :summary} in the epic's order (intro, lifecycle, graph, json, autonomous, writes); topic-text slurps the classpath resource and drops leading YAML frontmatter, which only SKILL.md (intro) carries. The dispatcher in main.clj tries resolve-cmd-key first, then "topics", then a topic, then the old unknown-command error, which now names `knot help topics`; top-level help gained one line under the per-command hint. The topics listing colors its names cyan like the command listing, with width math on the uncolored name.

Tests: a parity test derives the expected topic names from the files under resources/knot/skill (SKILL.md -> intro) and compares both directions, and forbids any topic named after a registry command name, alias, subcommand namespace, or "topics" itself. topic-text is pinned against the files on disk — the five references byte for byte, intro as a suffix of SKILL.md starting at its H1 — verified red under a deliberate mangling of the strip. Routing is exercised through the CLI for every topic. help_test's run-knot helper now puts resources/ on the subprocess classpath: bb -cp src does not merge bb.edn's :paths, so io/resource found nothing there.

501 tests / 5842 assertions, 0 failures. clj-kondo 0/0.

Deferred, deliberately: the H1 titles still read "Listing filters and columns" / "Knot JSON protocol" / "Lifecycle gates" rather than their topic names (retitling forces a byte-identical re-copy into .claude/skills/knot and a README link pass — worth a ticket of its own). No CHANGELOG entry: post-v0.12.0 feature commits add none, the release writes it, and the epic assigns the records sync to kno-01m21ad0d05h, which also owns the ADR note that pull now serves the pointer surface's bytes verbatim. jolt/deps.edn already carries ../resources, so the native build sees the guides.
