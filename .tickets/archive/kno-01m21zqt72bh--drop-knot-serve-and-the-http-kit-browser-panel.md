---
id: kno-01m21zqt72bh
title: Drop knot serve and the http-kit browser panel
status: closed
type: chore
priority: 2
mode: hitl
created: '2026-09-09T02:25:19.536513828Z'
updated: '2026-09-15T18:12:09.898426608Z'
closed: '2026-09-15T18:12:09.898426608Z'
tags:
- cleanup
- cli
- web-ui
acceptance:
- title: knot serve is gone from the command registry, knot --help, and knot help serve reports an unknown command
  done: true
- title: src/knot/serve.clj, both serve test files, and resources/knot/serve/ are deleted; resources stays on :paths
  done: true
- title: The bbin install steps are removed from ci.yml and CI is green on all three platforms
  done: true
- title: A new ADR supersedes 0005, 0006 and 0007 and the three are cross-linked to it
  done: true
- title: CHANGELOG has an Unreleased entry and jolt/README.md no longer lists serve as a gap
  done: true
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

## Notes

**2026-09-15T17:41:23.782180194Z**

Grilling decisions:
- New ADR 0020 records the retirement (knot stays daemon-less, no browser/server surface). 0005-0007 each get a Superseded note at the top linking to it.
- ADR 0011 gets a one-line superseded-in-part note: the serve graph consumer is gone. The always-on leverage field stands on its other reasons.
- CHANGELOG: released entries stay as they are. Unreleased gets a BREAKING Removed entry, minor bump (0.14.0).
- jolt: measure --tree-shake after removal and write the real result in jolt/README.md. flatland.ordered.set probably still blocks it; fixing that is a separate ticket. Also check whether MessageDigest is still used outside serve.
- deps.edn :paths comment: resources is there for the skill now, not serve.
- ci.yml: drop BBIN_VERSION too if nothing else uses it.

**2026-09-15T17:50:17.571945551Z**

jolt profile, v0.8.5, 159 tickets, median of 20 runs:
- Tree-shake is still skipped after removal. The one remaining blocker is flatland.ordered.set/hasheq-ordered-set -> resolve. Before removal, knot.serve/serve-cmd added four more resolve sites.
- Binary size: 18,035,604 bytes before, 17,943,287 after (about 92 KB smaller).
- Runtime is unchanged within noise. jolt before/after: --help 130/130 ms, list 208/204, check 247/243, show 267/267, RSS 191/190 MB. bb build:bb: 81 / 100 / 107 / 117 ms at 115-128 MB.
- Only serve used MessageDigest. The jolt build needs no crypto library now.
- bb test against the jolt binary: 41 failures and 1 error, all in topic-routing-test, skill-install-test and data-shape-skill-install-test. Cause: jolt/deps.edn has no :jolt/build :embed, so resources/knot/skill/* is not in the binary. This gap dates from the skill moving to resources/ (kno-01m21aczttph), not from serve. It is recorded in jolt/README.md and not fixed here.

**2026-09-15T18:12:09.898426608Z**

Removed knot serve end to end in c7b3951: serve namespace, tests and resources/knot/serve/ deleted, help registry entry dropped, knot serve and knot help serve now report an unknown command. CI no longer installs bbin or knot. ADR 0020 supersedes 0005-0007, and 0011 notes the serve graph is no longer planned. CHANGELOG has a BREAKING Removed entry. jolt/README.md drops serve as a gap. Measured on jolt 0.8.5: tree-shake is still blocked by flatland.ordered.set, and help topics and skill install fail in the binary because resources/ is not embedded. jolt 0.8.8 was tried and rejected: re-seq with (?m) anchors is quadratic there, so knot check hangs. Staying on 0.8.5, reported upstream by the user. AC 3 (CI green on all platforms) was checked off before a push and still needs confirming on CI.
