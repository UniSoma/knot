---
id: kno-01kqb7833py3
status: open
type: feature
priority: 2
mode: hitl
created: '2026-04-28T23:37:02.837973325Z'
updated: '2026-04-28T23:37:02.837973325Z'
tags:
- release
- infra
- v0.1
---

# Add release infrastructure: version constant, CHANGELOG, fix /release slash command

## Description

Trying to cut v0.0.1 surfaced gaps in release tooling:

- The `/release` slash command points at `src/aishell/cli.clj` line 13 expecting `(def version "X.Y.Z")` — both the path (now `src/knot/cli.clj`) and the constant are absent. The slash command was written before the project rename.
- No `CHANGELOG.md` at the repo root.
- No git tags exist yet (`git describe --tags --abbrev=0` fails).
- No `.github/workflows/` directory, so the slash command's step 7 ("Trigger Release workflow on GitHub Actions") has nothing to trigger.
- Distribution today is via bbin (`bb.edn` `:bbin/bin`). Release flow should fit that path before any GHA scaffolding is added.

## Design

Open questions to resolve before implementation:

1. **Version constant location** — `knot.cli` (matches the stale slash-command), `knot.main` (closer to the CLI entrypoint and `--version` plumbing), or a tiny `knot.version` namespace? Single source of truth either way.

2. **`--version` flag wiring** — surface it through `babashka.cli/dispatch` so `knot --version` (and `-v`) prints and exits 0. The help system added in 84e7f94 has no version line yet — fold it into the same renderer or keep it as a top-level shortcut?

3. **CHANGELOG seed for 0.0.1** — squash all pre-tag commits into one "Initial release" entry, or itemize the v0 stories (init, status lifecycle, deps graph, links, notes/edit, ready/blocked/closed, prime, help)? Itemizing matches the granular commit log; squashing matches the "first cut" framing.

4. **Tag format** — `/release` mandates `vX.Y.Z`. Confirm and record.

5. **GitHub Actions** — defer to a follow-up ticket, or include a minimal workflow that runs `bb test` on tag push and (optionally) publishes a release artifact?

6. **Slash command refresh** — update `/release` to reference `src/knot/...`, the correct project name, and to handle the no-prior-tag case gracefully (`git describe` fails on an unreleased repo).

7. **bbin coordinate** — does the bbin install command in the README need a version-pinned ref after the first tag, or does it follow main automatically? Document either way.

## Acceptance Criteria

- [ ] Version constant defined in a single agreed location and exposed so `knot --version` prints it
- [ ] `CHANGELOG.md` exists at the repo root in Keep a Changelog format with an initial 0.0.1 section
- [ ] `/release` slash command updated to reference correct paths and project name, and to handle the no-prior-tag case
- [ ] Tag format (`vX.Y.Z`) documented in `/release` and/or CHANGELOG header
- [ ] Decision recorded (in this ticket or a follow-up) on whether a GitHub Actions release workflow ships now
- [ ] README distribution snippet reviewed for any version-pinning implications post-tag
- [ ] First tag `v0.0.1` cut once the above land
