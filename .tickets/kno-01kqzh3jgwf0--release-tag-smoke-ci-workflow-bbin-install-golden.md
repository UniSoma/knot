---
id: kno-01kqzh3jgwf0
title: 'Release-tag smoke CI workflow: bbin install + golden-path check on tag push'
status: open
type: feature
priority: 3
mode: afk
created: '2026-05-06T20:54:09.174084540Z'
updated: '2026-05-06T21:36:48.975272046Z'
tags:
- v0.4
- ci
- release
- bbin
acceptance:
- title: release-smoke.yml workflow exists at .github/workflows/release-smoke.yml
  done: false
- title: Workflow triggers on tags matching v*
  done: false
- title: Workflow installs babashka + bbin + knot from the checkout
  done: false
- title: Smoke covers --version assertion against the tag, --help, init/create/ls, --json envelope shape, migrate-ac idempotence, check passes
  done: false
- title: Workflow runs on ubuntu-latest and macos-latest at minimum
  done: false
links:
- kno-01kqgqfwk4h1
- kno-01kqzkhpc244
---

## Description

Today release verification is manual: developer runs `bbin install` from a clean tmpdir post-push and eyeballs `knot --version` / `init` / `create` / `ls`. v0.3 was cut this way and it was fine, but the failure modes the smoke catches (busted `bb.edn :bbin/bin` manifest, stale version.clj, runtime crash on golden path, JSON envelope drift) are exactly the kind of thing CI is good at catching automatically — and the cost of missing them post-push is a tag that has to be retracted or hot-fixed.

Existing CI (`.github/workflows/ci.yml`) covers `bb test` + lint on Linux/macOS/Windows. There is no workflow that exercises the *installed* shim — the artifact users actually consume.

## Design

New workflow `.github/workflows/release-smoke.yml`:

**Trigger:** `on: push: tags: [v*]` (only fires on version tags, not branch pushes).

**Matrix:** `ubuntu-latest`, `macos-latest` (Windows bbin is a separate ticket if needed; v0.3 went green on the Windows blocking gate already, so the Windows install path can land later).

**Steps:**

1. Checkout repo at the just-pushed tag (already the default for tag pushes).
2. Install babashka (use `turtlequeue/setup-babashka` or the script-based install used by ci.yml).
3. Install bbin (`bash <(curl -s https://raw.githubusercontent.com/babashka/bbin/main/bbin)` or whatever pattern bbin documents).
4. Install knot from the local checkout: `bbin install . --as knot-smoke`.
5. Smoke commands:
   - `knot-smoke --version` → assert output equals tag minus the `v` prefix
   - `knot-smoke --help` (exit 0)
   - `mkdir _smoke && cd _smoke && knot-smoke init && knot-smoke create 'CI smoke' && knot-smoke ls`
   - `knot-smoke --json ls` → pipe to `jq` and verify `.ok == true` and `.schema_version` present
   - `knot-smoke migrate-ac` (idempotent on empty project — no-op exit 0)
   - `knot-smoke check` (no errors)
6. `bbin uninstall knot-smoke` (cleanup, optional in CI)

**Failure mode:** if any step exits non-zero, the tag-smoke job fails. The tag remains pushed (we don't gate the tag itself), but the failure is visible in GitHub Actions and on the Releases page workflow status.

**Optional follow-on:** post a comment to the GitHub Release (or open an issue) on smoke failure. Defer; first cut is just 'green/red signal'.
