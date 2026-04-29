---
id: kno-01kqcpb0t5s7
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-29T13:20:01.861232445Z'
updated: '2026-04-29T14:26:02.746799096Z'
deps:
- kno-01kqcpw6bzn6
---

# Refine distribution model: pinning, channels, native-image

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
