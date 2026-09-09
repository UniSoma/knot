---
id: kno-01m21zqt72bh
title: Drop knot serve and the http-kit browser panel
status: open
type: chore
priority: 2
mode: hitl
created: '2026-09-09T02:25:19.536513828Z'
updated: '2026-09-09T02:25:19.536513828Z'
tags:
- cleanup
- cli
- web-ui
acceptance:
- title: knot serve is gone from the command registry, knot --help, and knot help serve reports an unknown command
  done: false
- title: src/knot/serve.clj, both serve test files, and resources/knot/serve/ are deleted; resources stays on :paths
  done: false
- title: The bbin install steps are removed from ci.yml and CI is green on all three platforms
  done: false
- title: A new ADR supersedes 0005, 0006 and 0007 and the three are cross-linked to it
  done: false
- title: CHANGELOG has an Unreleased entry and jolt/README.md no longer lists serve as a gap
  done: false
links:
- kno-01krxmwemy05
- kno-01kqcpb0t5s7
- kno-01m21aczttph
---

## Description

`knot serve` was productionised from a prototype in kno-01krxmwemy05 as a read-only loopback browser panel. It is an experiment: nothing in the repo consumes it, `knot.el` shells out to the CLI directly, no `--json` contract or check-code catalogue mentions it, and the README promises "no daemons, no servers". It is also the one command the jolt binary cannot run and the reason `--tree-shake` is skipped there, because `start-server!` reaches http-kit through `resolve`.

Today's footprint:

- `src/knot/serve.clj` (whole namespace) and its registration at `src/knot/main.clj:12,1321-1326,1429` and `src/knot/help.clj:668-687,701`.
- `test/knot/serve_test.clj`, `test/knot/serve_integration_test.clj`, `serve-registered-test` in `test/knot/help_test.clj:409-424`, and the two `serve --help` routing cases in `test/knot/integration_test.clj:1144-1159`.
- `resources/knot/serve/public/{index.html,app.js,styles.css}`, the only files under `resources/`.
- `.github/workflows/ci.yml:34-58`: the "Install bbin" and "Install knot from checkout" steps exist only so the serve integration test can spawn an installed `knot`.
- ADRs 0005, 0006, 0007 record decisions about the panel; ADR 0011 names a future serve graph as a consumer of `leverage`.
- Prose: `CHANGELOG.md:25` and `jolt/README.md` describe serve as the jolt gap and the tree-shake blocker.

## Design

Remove the command end to end and record the retirement rather than erase the reasoning. The `resources` entry stays on `:paths` in `bb.edn` and `deps.edn`: kno-01m21aczttph is about to put the knot skill there. Retire ADRs 0005 to 0007 with a single superseding ADR stating that the browser surface was dropped and why, and cross-link the three from it as ADR 0009 did for 0003. Per ADR 0017, the help registry moves in the same commit as the code; the skill and prime surfaces never mentioned serve, so they need no change. After removal, confirm whether the jolt binary's `--tree-shake` now runs and update `jolt/README.md` with whatever the measurement says.
