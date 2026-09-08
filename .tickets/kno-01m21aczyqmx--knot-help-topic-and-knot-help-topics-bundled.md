---
id: kno-01m21aczyqmx
title: 'knot help <topic> and knot help topics: bundled concept guides'
status: open
type: feature
priority: 2
mode: afk
created: '2026-09-08T20:12:24.902552796Z'
updated: '2026-09-08T20:12:24.902552796Z'
parent: kno-01m21abe2vqs
tags:
- cli
- docs
acceptance:
- title: knot help <topic> prints the bundled markdown for each of the six topics and exits 0; knot help topics lists all six with summaries
  done: false
- title: A test asserts no topic name equals any registry command name, alias, or subcommand, and that every resources/knot/skill topic file has a topics entry and vice versa
  done: false
- title: knot help <command> and knot <command> --help behave exactly as before; knot help bogus exits 1 and names knot help topics
  done: false
- title: knot --help top-level output advertises knot help topics in one line
  done: false
deps:
- kno-01m21aczttph
---

## Description

Add a `topics` def to `knot.help` mapping topic name → classpath resource + one-line summary, for the six topics shipped by the parent's first child. `knot help <topic>` prints the resource verbatim (no color, no paging); `knot help topics` lists `name  summary`. The `help` dispatcher in `main.clj` resolves commands, aliases and subcommands first and falls back to topics; an unknown word keeps today's `knot help: unknown command` error but mentions `knot help topics`.

Add one line to the top-level `knot --help` under the "Run `knot help <command>`" sentence: concept guides are at `knot help topics`. No inventory of topic names in prose anywhere else (ADR 0017 rule).

Prior art: `git help -g`, `gh help environment`, `jj help -k`.
