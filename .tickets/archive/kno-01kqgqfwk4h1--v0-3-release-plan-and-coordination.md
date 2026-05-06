---
id: kno-01kqgqfwk4h1
title: v0.3 release plan and coordination
status: closed
type: task
priority: 3
mode: hitl
created: '2026-05-01T02:57:07.684531365Z'
updated: '2026-05-06T21:28:45.503663828Z'
closed: '2026-05-06T21:28:45.503663828Z'
tags:
- v0.3
- release
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqbjg012
- kno-01kqgqa1jj1s
- kno-01kqgqcqmy19
- kno-01kqgqc2ks70
- kno-01kqgqdxbxye
- kno-01kqgqa7wnep
- kno-01kqgqegm782
- kno-01kqgqf4aw4j
links:
- kno-01kqcpb0t5s7
- kno-01kqcvp72htb
- kno-01kqzh1tadw8
- kno-01kqzh2vhhrz
- kno-01kqzh3jgwf0
acceptance:
- title: Merge order pinned and recorded in this ticket's notes
  done: true
- title: AC migration timing decided (first-invocation auto-run / explicit `knot init --migrate` / bash script)
  done: true
- title: Single CHANGELOG entry drafted covering every v0.3 breaking change
  done: true
- title: Release notes drafted with migration guidance for v0.2 users
  done: true
- title: All v0.3-tagged blockers closed before tag push
  done: true
- title: '`bb test` green on all platforms (or Windows ticket kno-01kqcvp72htb explicitly resolved or deferred)'
  done: true
- title: v0.3 tag pushed; bbin install verified end-to-end
  done: true
---

## Description

Coordinate the v0.3 release once the v0.3 implementation slices are done. This is **not** the implementation work — it is the cut.

**v0.3 minimum bar (the slices that must land before tagging):**

1. kno-01kqgq9vhmvr — JSON envelope on read commands (foundation)
2. kno-01kqgqbjg012 — `--json` on all mutating commands
3. kno-01kqgqa1jj1s — Uniform filter flag set across listing + `prime`
4. kno-01kqgqcqmy19 — `knot update` command
5. kno-01kqgqc2ks70 — `knot check` command + `dep cycle` removal
6. kno-01kqgqdxbxye — Acceptance criteria promoted to frontmatter
7. kno-01kqgqa7wnep — `--afk`/`--hitl` shortcut removal
8. kno-01kqgqegm782 — `knot schema` introspection
9. kno-01kqgqf4aw4j — README + JSON protocol docs

**Sequencing decisions to settle:**

- Order of merges (envelope must land before `--json`-on-mutations; mutations + envelope must land before schema introspection; AC migration runs at cut, not before)
- Single bundled v0.3 PR vs sliced PRs landing on main as they're ready
- AC migration script timing — runs on first invocation post-upgrade, or as a one-time `knot init --migrate` command, or as a bash script in the release notes
- CHANGELOG drafting — coordinate the breaking-change list across slices; one consolidated CHANGELOG entry rather than per-slice noise
- Release notes phrasing — emphasize that v0.2 → v0.3 breaks (envelope shape, removed flags, AC migration); document the migration path

**Open follow-ups not blocking v0.3:**
- kno-01kqgqafcxvv — reopen atomicity verification (could land in v0.3 if found broken)
- kno-01kqgqapwqvh — hardcoded-literal audit (audit can run in parallel; child fixes may slip)
- kno-01kqgqd3vzx6 — stale-in-progress consolidation under `check` (hitl decision)
- kno-01kqgqaxzx98 — future-work optimistic concurrency (deferred)

**Cut steps:**

- All v0.3-tagged tickets above are closed
- `bb test` green on all platforms (depend on kno-01kqcvp72htb for Windows)
- CHANGELOG entry merged
- Tag pushed; bbin distribution updated (coordinate with kno-01kqcpb0t5s7 if distribution model is changing)

## Notes

**2026-05-06T20:55:00.534314929Z**

Grilling outcomes — design decisions for the cut and 12-step manual cut sequence.

**2026-05-06T21:28:45.503663828Z**

v0.3.0 cut and shipped. Tag v0.3.0 (annotated, with migration prose) pushed to origin/main; bbin install verified end-to-end on the public tag (--version=0.3.0, init/create/ls/--json envelope shape, migrate-ac idempotent, check ok).

Cut breakdown:
- Manual 12-step sequence (`/release` slash command was v0.2-shaped and not used; modernization filed as kno-01kqzh2vhhrz)
- Coverage audit at cut time backfilled 4 missed slices into [Unreleased] before the rename: --add-ac/--remove-ac (kno-01kqxchq706w), monotonic ULID id-collision fix (kno-01kqjavvr89d), config-driven intake status (kno-01kqsgmey8dm), config-driven AFK preamble via :afk-mode (kno-01kqsgmey9ew). Two other closed v0.3 tickets were intentionally not added: schema command (kno-01kqgqegm782, won't-do — absorbed into docs slice + JSON contract test suite) and archive-subdir constant (kno-01kqtd4qthd3, internal refactor with no user-visible behavior change).
- Release-notes prose drafted to a temp release-notes-v0.3.txt at repo root; used as both annotated tag body (`git tag -a -F` with --cleanup=verbatim — default 'strip' mode eats markdown ## headings) and copy source for the GitHub Release body. File deleted post-tag.
- Pre-push smoke (`bbin install . --as knot-rc`) caught nothing (clean); post-push smoke from public tag (`bbin install https://github.com/UniSoma/knot.git --as knot-v030` in /tmp/knot-smoke) ran the full golden path.

Settled decisions captured in this ticket's prior 'Grilling outcomes' notes block (8 decisions; sequence; follow-up IDs).

v0.4 follow-ups linked and filed: kno-01kqzh1tadw8 (knot check legacy AC body warning), kno-01kqzh2vhhrz (modernize /release), kno-01kqzh3jgwf0 (release-tag smoke CI).

Linked v0.3 prerequisites both closed: kno-01kqcvp72htb (Windows green) and the JSON-envelope/structured-AC slices that make up v0.3's user-visible value. Distribution refinement (kno-01kqcpb0t5s7) intentionally not blocked on; v0.3 ships on the existing bbin model.

Final state: tests 326/4187/0; lint baseline preserved (4 errors / 5 warnings, all pre-existing). Commits: c469c27 (chore: follow-ups + grilling), 4d48da1 (Release v0.3.0). Tag: v0.3.0 (annotated, 66-line body, --cleanup=verbatim).

## Decisions settled

**1. Independence from distribution refinement (kno-01kqcpb0t5s7).** v0.3 ships on the existing bbin model. The release-plan body's 'coordinate with kno-01kqcpb0t5s7' clause is satisfied by 'install path unchanged from v0.2' in release notes. Distribution refinement is independent v0.4+ infra work.

**2. AC migration UX.** Hidden `knot migrate-ac` subcommand (already shipped with `:hidden? true` in help.clj:477) stays hidden. Discoverability comes from release notes prose, not from `--help` cruft. `knot check` discoverability nudge filed as kno-01kqzh1tadw8 (v0.4 follow-up).

**3. CHANGELOG: rename, don't compress.** `[Unreleased]` is already organized into Added/Changed/Changed (BREAKING)/Removed/Fixed buckets — that's the right structure. Pure rename to `[0.3.0] - <date>` at cut, fresh empty `[Unreleased]` skeleton above. Slice-by-slice detail is preserved as audit trail; human-readable summary lives in release notes, not CHANGELOG. **Audit pass at cut**: walk `knot list --tag v0.3 --status closed` and verify each is reflected. Two known gaps to backfill: `--add-ac`/`--remove-ac` (kno-01kqxchq706w, commit d3795bb) and monotonic ULID id-collision fix (kno-01kqjavvr89d). Anything else found in the audit goes in too.

**4. Release notes: annotated tag + GitHub Release body, single shared prose.** Floor: `git tag -a v0.3.0 -F release-notes-v0.3.txt` so `git show v0.3.0` carries the migration message even without GitHub. Ceiling: GitHub Release body uses the same prose. No separate in-repo `docs/release-notes/` doc — establishes a precedent the project hasn't committed to maintaining; duplicates CHANGELOG. Prose structure: highlights / breaking changes with one-liner migrations / upgrade path (`bbin install` then `knot migrate-ac`) / link to `[0.3.0]` CHANGELOG anchor.

**5. Cut tooling.** v0.3 is cut **manually**. The `/release` slash command is a v0.2-shaped script with real gaps (Step 4 'add new section' would corrupt this CHANGELOG; Step 6 produces a lightweight tag; no audit / prose / smoke steps). Cut day is the wrong time to refactor cut tooling. Slash-command modernization filed as kno-01kqzh2vhhrz (v0.4 follow-up).

**6. Post-tag verification.** Manual smoke split into pre-push (working-copy install — safety net before the tag goes public) and post-push (clean tmpdir install from the public tag). Catches busted bb.edn manifest, stale version, runtime crash on golden path, JSON envelope shape drift, migrate-ac regression. Automated release-tag CI workflow filed as kno-01kqzh3jgwf0 (v0.4 follow-up).

**7. Tag signing.** Not part of project precedent (v0.2.0 is a lightweight tag, no signing config). Don't introduce for v0.3 — different question, different ticket if ever wanted.

**8. Merge strategy.** Settled by behavior: slices already merged on main as ready (no bundled v0.3 PR exists). The release-plan body had this as a sequencing decision; resolved-in-practice rather than resolved-by-decision.

## 12-step manual cut sequence

1. **Coverage audit** — `knot list --tag v0.3 --status closed` → verify each is in `[Unreleased]`. Backfill the two known gaps + anything else.
2. **Draft `release-notes-v0.3.txt`** at repo root (temp; deleted at step 11). Highlights / breaking / upgrade / CHANGELOG link.
3. **Rename CHANGELOG**: `[Unreleased]` → `[0.3.0] - <date>`, add fresh empty `[Unreleased]` skeleton above.
4. **Bump `src/knot/version.clj`** 0.2.0 → 0.3.0.
5. **Run `bb test` + `clj-kondo --lint src test`** — green required.
6. **Pre-push smoke**: `bbin install . --as knot-rc`; check `--version` / `--help` / `info` / `check`; `bbin uninstall knot-rc`.
7. **Commit** `Release v0.3.0` (matching v0.2 commit message style — no Conventional Commits prefix; no AI attribution per AGENTS.md).
8. **Annotated tag**: `git tag -a v0.3.0 -F release-notes-v0.3.txt`.
9. **Push**: `git push origin main --tags`.
10. **Post-push smoke**: clean tmpdir → `bbin install https://github.com/UniSoma/knot.git --as knot-v030`; `--version` (=0.3.0) / `init` / `create` / `ls` / `--json ls` (envelope shape OK) / `migrate-ac` (idempotent no-op) / `check`; `bbin uninstall`.
11. **Create GitHub Release** at github.com/UniSoma/knot/releases/new with the same prose body (tag-target v0.3.0). Delete `release-notes-v0.3.txt`.
12. **Close kno-01kqgqfwk4h1** with cut summary.

## Follow-ups filed (v0.4)

- kno-01kqzh1tadw8 — `knot check` warning for legacy `## Acceptance Criteria` body section (discoverability nudge for migrate-ac)
- kno-01kqzh2vhhrz — Modernize `/release` slash command for v0.3-shape cuts
- kno-01kqzh3jgwf0 — Release-tag smoke CI workflow (automate the post-push smoke)
