---
id: kno-01kqzh3jgwf0
title: 'Release-tag smoke CI workflow: bbin install + golden-path check on tag push'
status: closed
type: feature
priority: 4
mode: hitl
created: '2026-05-06T20:54:09.174084540Z'
updated: '2026-05-17T00:38:29.720490108Z'
closed: '2026-05-17T00:38:29.720490108Z'
tags:
- ci
- release
- bbin
- v0.5
acceptance:
- title: release-smoke.yml workflow exists at .github/workflows/release-smoke.yml
  done: true
- title: Workflow triggers on tags matching v*
  done: true
- title: Workflow installs babashka + bbin + knot from the checkout
  done: true
- title: Smoke covers --version tag-equality assertion, --help, init/create/ls/show/start/close lifecycle, --json envelope shape (ls --json), and check passes on a fresh project
  done: true
- title: Workflow runs on ubuntu-latest, macos-latest, and windows-latest
  done: true
links:
- kno-01kqgqfwk4h1
parent: kno-01krhwcy0zdy
---

## Description

Today release verification is manual: developer runs `bbin install` from a clean tmpdir post-push and eyeballs `knot --version` / `init` / `create` / `ls`. v0.3 was cut this way and it was fine, but the failure modes the smoke catches (busted `bb.edn :bbin/bin` manifest, stale version.clj, runtime crash on golden path, JSON envelope drift) are exactly the kind of thing CI is good at catching automatically — and the cost of missing them post-push is a tag that has to be retracted or hot-fixed.

Existing CI (`.github/workflows/ci.yml`) covers `bb test` + lint on Linux/macOS/Windows. There is no workflow that exercises the *installed* shim — the artifact users actually consume.

## Design

New workflow `.github/workflows/release-smoke.yml`. Detect-after-push model — see [ADR-0004](../../docs/adr/0004-release-tag-smoke-detects-post-push-not-gate.md). The tag stays on origin regardless of smoke outcome; `/release` Step 11 (`gh release create`) is not gated on smoke.

**Trigger.** `on: push: tags: [v*]` plus `workflow_dispatch: {}` (manual re-run escape hatch).

**Matrix.** `ubuntu-latest`, `macos-latest`, `windows-latest`. bbin supports Windows via scoop or manual install ([docs](https://github.com/babashka/bbin/blob/main/docs/installation.md#manual-windows)) — the workflow uses the manual path with `shell: bash` (Git Bash on Windows ships `curl`). `fail-fast: false` so a single-platform break does not hide the others.

**Versions** declared at top-level `env:` so bumps are one place:

- `BB_VERSION: 1.12.218` (matches `ci.yml`).
- `BBIN_VERSION: v0.2.5` (pinned, not `main` — a breaking change on `main` should not silently break release-smoke).

**Steps (single `smoke` job, matrix-parallelised):**

1. `actions/checkout@v4`.
2. `DeLaGuardo/setup-clojure@13.6.0` with `bb: ${{ env.BB_VERSION }}` (consistent with `ci.yml`).
3. **Install bbin** via a single `shell: bash` step. Drops `bbin` into `$HOME/.local/bin`, additionally drops `bbin.bat` on Windows (`$RUNNER_OS == "Windows"`), prepends `$HOME/.local/bin` to `$GITHUB_PATH`.
4. **Install knot** from the checkout: `bbin install . --as knot-smoke`.
5. **Smoke — bootstrap:**
   - `[ "$(knot-smoke --version)" = "${GITHUB_REF_NAME#v}" ]` — exact tag-equality (rejects `0.5.0-SNAPSHOT` shaped drift).
   - `knot-smoke --help > /dev/null`.
6. **Smoke — lifecycle** (in `mktemp -d`, `set -euo pipefail`):
   - `knot-smoke init`
   - `id=$(knot-smoke create -t "CI smoke" --type chore --json | jq -r .data.id)`
   - `knot-smoke ls`
   - `knot-smoke ls --json | jq -e '.ok == true and (.schema_version|type) == "number"'`
   - `knot-smoke show "$id"`
   - `knot-smoke start "$id"` (exercises `*→active` and the open-children gate)
   - `knot-smoke close "$id" --summary "smoke complete"` (exercises `active→terminal`, acceptance gate, open-children gate)
   - `knot-smoke check` (zero problems on fresh project)

**No concurrency block** — tags are unique per release, the `ci-${{ github.ref }}` pattern used by `ci.yml` adds nothing here.

**No trailing `bbin uninstall`** — CI runners are ephemeral; cleanup is noise.

**Deliberately dropped from the original Design:**

- `knot-smoke migrate-ac` — v0.3 one-shot migration; on a fresh tmpdir project it exits 0 trivially without exercising current code paths.
- `knot-smoke info --json` — redundant with `ls --json` for envelope-shape assertion.
- Trailing `bbin uninstall knot-smoke` — runner-ephemeral makes it noise.

**Deferred to follow-up tickets (not part of this ticket):**

- PR-time smoke (path-filtered on bb.edn / version.clj / workflows). Revisit once tag-time smoke has caught something real.
- Smoke-failure notification (GH Release comment, opened issue, etc.). First cut is green/red signal only.
- Gate-the-GH-Release variant. ADR-0004 records the decision and the contained change set if revisited.

## Notes

**2026-05-17T00:34:59.448108961Z**

Design refreshed after a grilling session resolving all open branches. Outcomes: (1) detect-after-push (not gate-release) — captured in ADR-0004; (2) three-platform matrix including windows-latest (bbin manual Windows install via PowerShell-equivalent with shell: bash + bbin.bat shim); (3) bb 1.12.218 + bbin v0.2.5 pinned via top-level env block; (4) install plumbing: single shell:bash step using Git Bash on Windows, $HOME/.local/bin canonical install dir on all platforms; (5) 10-command lifecycle smoke (init/create/ls/ls --json/show/start/close/check + bootstrap --version/--help); migrate-ac dropped (v0.3 vestige), info --json dropped (redundant); (6) workflow_dispatch trigger added; single job, no concurrency block, no trailing uninstall; (7) version assertion via exact equality, not grep. CONTEXT.md gained 'Release verification gates' section naming CI test / pre-push smoke / release-tag smoke as three distinct gates.

**2026-05-17T00:38:29.720490108Z**

Added .github/workflows/release-smoke.yml: detect-after-push CI smoke triggered on v* tag push and via workflow_dispatch, three-platform matrix (ubuntu/macos/windows-latest), bb 1.12.218 + bbin v0.2.5 pinned via top-level env, single shell:bash bbin install (manual download with .bat shim on Windows), bbin install . --as knot-smoke from checkout, bootstrap smoke (--version tag-equality + --help) plus 8-command lifecycle smoke in mktemp -d (init/create/ls/ls --json envelope/show/start/close/check). Captures detect-vs-gate decision as ADR-0004 and names the three verification gates in CONTEXT.md (CI test, pre-push smoke, release-tag smoke). Design + ACs walked through a grilling session before implementation.
