---
id: kno-01kqa6xff7ya
title: Distribution via bbin and README
status: closed
type: task
priority: 2
mode: afk
created: '2026-04-28T14:12:00.615581953Z'
updated: '2026-04-28T21:20:53.339307617Z'
closed: '2026-04-28T21:20:53.339307617Z'
parent: kno-01kqa804gmgx
external_refs:
- docs/prd/knot-v0.md
- issues/0011-distribution-and-readme.md
deps:
- kno-01kqa6xfe80k
---
## Parent document

`docs/prd/knot-v0.md`

## What to build

Make Knot installable via `bbin install io.github.<user>/knot --as knot` and ship a README that introduces the tool, its philosophy, and the most common workflows. The `bb.edn` already declared `clj-yaml` (slice 1); this slice adds whatever metadata bbin needs (paths, main entry) and verifies the install path works end-to-end. The README documents installation, the personal-driver framing, the no-locks design, and the SessionStart hook setup (referenced from slice 10).

## User stories covered

- 29 (no lock files — documented as a deliberate choice for personal use on a single machine)
- 39 (`bbin install io.github.<user>/knot --as knot` sets up Knot)

## Acceptance criteria

- [ ] `bb.edn` exposes a `:bbin/bin` entry pointing at `knot.main`
- [ ] `bbin install io.github.<user>/knot --as knot` installs successfully from a tagged release (or local path during dev)
- [ ] Manual smoke test: in a fresh shell, after install, `knot init` + `knot create` + `knot ls` works end-to-end
- [ ] README sections: introduction (personal-driver framing), install, quick tour (init / create / start / ready / close), config schema reference, AI-agent integration (`prime` + SessionStart hook snippet from slice 10), philosophy notes (file-based, git-native, no locks, no daemons)
- [ ] README explicitly notes: no file locking; last writer wins; works for personal-driver use on a single machine
- [ ] README links to the PRD for design rationale
- [ ] No Homebrew tap, AUR package, or Makefile — bbin only

## Blocked by

- issue-0010 (`issues/0010-prime-and-session-start.md`)

## Notes

**2026-04-28T21:20:53.339307617Z**

Added :bbin/bin entry to bb.edn pointing at knot.main, plus a minimal deps.edn so bbin's generated shim can resolve :local/root via clojure.tools.deps (same pattern clj-surgeon uses). Rewrote README with required sections: install (bbin-only — no Homebrew/AUR/Makefile), quick tour, .knot.edn schema table, AI-agent integration with SessionStart hook snippet, and philosophy notes covering no-locks/last-writer-wins/single-machine personal-driver scope. Verified bbin install . --as knot end-to-end in a fresh tmp dir: knot init + knot create + knot ls (table and JSON) + knot prime all work via the installed shim. 140 tests / 1179 assertions still green.
