---
id: kno-01kqcpb0t5s7
title: 'Refine distribution model: pinning, channels, native-image'
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-29T13:20:01.861232445Z'
updated: '2026-08-27T20:41:14.112974689Z'
deps:
- kno-01kqcpw6bzn6
links:
- kno-01kqe94cgmd2
- kno-01kqgqfwk4h1
- kno-01m12f94nctd
tags:
- distribution
- future
---

## Description

Once v0.0.1 ships (tracking main via bbin), the install story will need to evolve as knot picks up users beyond the author. This ticket captures the decision space.

## Open questions

1. **Version pinning syntax.** Document the bbin invocation for installing a specific tag or sha (e.g. `bbin install io.github.UniSoma/knot --git/sha <sha>` or the tag-ref equivalent). Today the README only shows `bbin install <url>`, which snapshots main at install time.

2. **Stable vs latest channels.** Should the README document two install paths (stable = latest tag, latest = main HEAD), or pick one default?

3. **Native-image / binary distribution.** Investigate GraalVM native-image build for knot. Trade: faster startup, no bb dependency, but adds a build pipeline and per-OS binaries to maintain. Likely blocked by the GHA decision — without CI there's no place to produce binaries.

4. **Update flow.** `knot self-update` command, or rely on users re-running `bbin install`? bbin does not auto-update on its own.

5. **Release artifacts.** If GHA lands later, do we publish a jar / tarball / native binary on tagged GitHub Releases, or keep distribution purely git-ref-based?

## When this matters

Deferred for v0.0.1 — the project has one user and bbin-tracking-main is sufficient. Revisit when external users emerge or a binary-distribution story is needed.

## Notes

**2026-04-29T14:26:02.746799096Z**

https://github.com/unisoma/aishell has good ideas

**2026-08-27T20:05:36.623946431Z**

Native binary via jolt is proven: jolt/ (deps.edn + cheshire/clj-yaml shims, bb build:jolt) compiles knot to an 18.6 MB self-contained ELF that passes the full bb test suite when the integration tests target the binary. Gaps: knot serve (http-kit is Java), startup ~160 ms vs bb ~110 ms, list/check slower from the Clojure YAML shim. Upstream jolt bugs found: str/last-index-of rejects a char, Matcher.find(int) ignores its offset, vendored babashka.fs/list-dir unbound. See jolt/README.md.

**2026-08-27T20:22:04.238150530Z**

bb uberjar + bb binary concatenation (bb build:bb -> target/knot, 68 MB) works unchanged and matches bb -m knot.main byte for byte. Timings vs jolt/knot on 149 tickets: --help 80 vs 155 ms; ready/list/prime/create ~100 vs ~300 ms; check 120 vs 350 ms; close 103 vs 300 ms; show 115 vs 440 ms; peak RSS on check 100 vs 160 MB. Jolt's only win is size (18 MB). The gap is the pure-Clojure YAML shim; SnakeYAML parses the same tickets ~7x faster.
