---
id: kno-01kqcpw6bzn6
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-29T13:29:24.607894173Z'
updated: '2026-04-29T13:29:24.607894173Z'
---

# Add GitHub Actions CI: bb test on push + PR

## Description

Set up minimal CI on GitHub Actions so `bb test` runs automatically on every push to main and on pull requests. Currently there is no `.github/workflows/` directory; tests are only run locally.

## Scope

- `.github/workflows/ci.yml` that runs `bb test` on push to main and on PRs.
- Pin `babashka` to a known-good version (`bb.edn` declares `:min-bb-version 1.3.0`; pick a current stable release for CI).
- Decide on test matrix: single OS (ubuntu-latest) for v0, or fan out across linux/macos/windows.

## Out of scope (separate follow-ups)

- Release workflow on tag push — covered by kno-01kqcpb0t5s7 (distribution model) and the v0.0.1 release ticket (kno-01kqb7833py3).
- Native-image / binary builds — covered by kno-01kqcpb0t5s7.
- Coverage reporting, lint, static analysis — orthogonal; open separately if desired.

## Open questions

1. **OS matrix.** ubuntu-only (cheap, fast) or full matrix (catches platform-specific bugs but 3x cost)? For a babashka CLI consumed by humans on macOS/linux, ubuntu+macos is probably the sweet spot.
2. **bb version matrix.** Single pinned version, or test against `:min-bb-version` (1.3.0) plus latest? The latter would catch regressions where new code accidentally uses a post-1.3.0 feature.
3. **Caching.** Cache the bb install across runs (small win) — usually worth it.

## Why this is deferred from kno-01kqb7833py3

The v0.0.1 release ticket explicitly chose to defer GHA to keep release-tooling scope tight. Distribution does not require CI (bbin reads `bb.edn` from the git ref directly), so this is a quality-of-life add, not a release blocker.
