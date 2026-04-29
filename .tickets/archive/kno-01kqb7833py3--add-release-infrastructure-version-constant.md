---
id: kno-01kqb7833py3
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-04-28T23:37:02.837973325Z'
updated: '2026-04-29T13:57:33.353030723Z'
closed: '2026-04-29T13:57:33.353030723Z'
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

## Notes

**2026-04-29T13:32:56.786365675Z**

## Design decisions (locked)

1. **Version constant** → new `knot.version` namespace (`src/knot/version.clj`), single `(def version "0.0.1")`. Decouples `/release` grep target from a busy `main.clj`.
2. **`--version` interception** → top-level shortcut at the head of `-main`, symmetric with the existing `--help` interception. Prints bare `0.0.1` and exits 0.
3. **`-v` shorthand** → not wired. Slot reserved for a future `--verbose`.
4. **Help banner** → `top-level-help-text` (in `knot.help`) gains a `knot v0.0.1` header line so `knot --help` surfaces the version too.
5. **CHANGELOG seed** → itemized at capability granularity (~10 bullets: project setup, lifecycle, discovery, deps, links, annotation, prime, help, distribution, JSON output). Sets the template for future entries.
6. **Tag format** → `vX.Y.Z`, recorded in CHANGELOG header and in the refreshed `/release`.
7. **GitHub Actions** → deferred entirely. No `.github/workflows/` in this ticket. Step 7 of `/release` ("Trigger Release workflow on GitHub Actions") gets dropped. Tracked separately in kno-01kqcpw6bzn6.
8. **Slash command location** → new project-local `.claude/commands/release.md`. User-global `~/.claude/commands/release.md` left untouched (still used in other projects). Claude Code resolves project-local first.
9. **No-prior-tag handling** → instructional prose in step 1: "if `git describe --tags --abbrev=0` fails, this is the first release; run `git log --oneline` instead." Lets Claude reason; no brittle conditional.
10. **Release commit message** → `Release vX.Y.Z`. Matches knot's verb-leading convention; no Conventional Commits prefix.
11. **README install snippet** → unchanged. Tracks main at install time. Pinning / channels / binary distribution deferred to kno-01kqcpb0t5s7.

## Follow-up tickets

- **kno-01kqcpw6bzn6** — Add GitHub Actions CI: `bb test` on push + PR (resolves AC: 'Decision recorded on whether a GHA workflow ships now')
- **kno-01kqcpb0t5s7** — Refine distribution model: pinning, channels, native-image (covers the deferred half of the README review AC)

## Implementation order (suggested)

1. Create `src/knot/version.clj` with `(def version "0.0.1")`.
2. Wire `--version` interception in `knot.main/-main`.
3. Add header line in `knot.help/top-level-help-text`.
4. Write `CHANGELOG.md` with 0.0.1 entry + Versioning note.
5. Create project-local `.claude/commands/release.md` (copy + edit from global).
6. Run `bb test` to confirm nothing broke.
7. Use the new slash command to cut `v0.0.1`.
