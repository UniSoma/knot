---
id: kno-01krxmwemy05
title: Productionise prototype/serve/ as 'knot serve' command
status: closed
type: feature
priority: 4
mode: hitl
created: '2026-05-18T13:37:23.102315484Z'
updated: '2026-05-18T15:39:01.321784074Z'
closed: '2026-05-18T15:39:01.321784074Z'
tags:
- prototype
- web-ui
acceptance:
- title: knot.serve namespace registered as 'knot serve' subcommand in knot.main; http-kit lazy-loaded inside the handler.
  done: true
- title: resources/ added to bb.edn :paths; assets moved from prototype/serve/public/ to resources/knot/serve/public/; --dev flag for disk fallback.
  done: true
- title: Foreground process with --open / --no-open / --port; tmpdir heartbeat at ${TMPDIR}/knot-serve-<sha256(project-root)[:12]>.json detects 'already running' and exits 0.
  done: true
- title: 'Origin allowlist middleware on /api/*: accepts missing / null / http://(127.0.0.1|localhost):<port>, rejects others.'
  done: true
- title: test/knot/serve_test.clj unit tests cover heartbeat-path, origin-allowed?, and route dispatch.
  done: true
- title: test/knot/serve_integration_test.clj boots the server on an ephemeral port, hits each /api/* route, asserts envelope shape and origin rejection.
  done: true
- title: bb test green; clj-kondo --lint src test clean.
  done: true
- title: 'Manual smoke: fresh ''bbin install --as knot-rc .'' then ''knot-rc serve'' in a sample project loads the Stack UI and reflects ticket state.'
  done: true
- title: prototype/serve/ removed; prototype:serve task removed from bb.edn; prototype/ removed if empty.
  done: true
- title: .claude/skills/knot/SKILL.md updated to mention 'knot serve'.
  done: true
- title: CHANGELOG entry under Unreleased.
  done: true
---

## Description

Promote `prototype/serve/` to a real `knot serve` subcommand.

Layout is fixed by ADR-0005 (single-column Stack). ADR-0006 (read-only v1) and ADR-0007 (shell-out per request) capture the two reviewer-surprising decisions. This ticket captures the productionisation design pass; the design forks are resolved.

## Decisions

- **Audience:** personal panel, single user, loopback only. Not a team dashboard. VSCode webview / Emacs xwidget are valid embedders but not v1 acceptance gates.
- **Surface:** read-only v1 (see ADR-0006). Writes deferred — gated on optimistic-concurrency work in kno-01kqgqaxzx98.
- **Process:** foreground; `--open` / `--no-open` / `--port` flags; tmpdir heartbeat at `${TMPDIR}/knot-serve-<sha256(project-root)[:12]>.json` to detect "already running for this project" without inventing a daemon.
- **Wire:** shells out to `knot ... --json` per request (see ADR-0007).
- **Runtime:** Babashka. http-kit lazy-loaded inside the `serve` handler so every other `knot` command stays cheap.
- **Assets:** classpath under `resources/knot/serve/public/`. `--dev` flag for filesystem fallback during local hacking.
- **Invocation:** subcommand of `knot`, lives at `src/knot/serve.clj`. Registered in `knot.main`'s command registry.
- **Hardening:** origin allowlist middleware on `/api/*`. Accepts missing / `null` / `http://(127.0.0.1|localhost):<port>`; rejects everything else.
- **knot.el relationship:** independent surface. A "launch knot serve from Emacs" bridge is a separate follow-up ticket if it ever earns priority.

Until this lands, the prototype stays runnable via `bb prototype:serve` (port 7777, read-only).

## Notes

**2026-05-18T15:39:01.321784074Z**

Promoted prototype/serve/ to a real 'knot serve' subcommand: src/knot/serve.clj with lazy-loaded http-kit, classpath assets under resources/knot/serve/public/ (with --dev disk fallback), --port / --open / --no-open flags, per-project heartbeat at ${TMPDIR}/knot-serve-<sha256(project-root)[:12]>.json, and an origin allowlist on /api/* (nil / null / loopback only). New test/knot/serve_test.clj covers origin-allowed?, heartbeat-path, route dispatch, id validation, and heartbeat round-trip; test/knot/serve_integration_test.clj boots http-kit on an ephemeral port and spawns the CLI to pin the wiring end-to-end. resources/ added to both bb.edn and deps.edn :paths (the deps.edn miss is what bbin reads). prototype/ removed; SKILL.md and CHANGELOG updated.
