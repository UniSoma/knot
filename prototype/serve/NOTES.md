# `knot serve` Web UI prototype

Throwaway prototype of a browser-rendered ticket panel that shells out to
`knot ... --json` for all reads. Read-only — no mutation endpoints.

Layout is the single-column **Stack** shape (three collapsible groups —
In progress / Ready / Blocked — click row to expand inline). See
[`docs/adr/0005-knot-serve-stack-layout.md`](../../docs/adr/0005-knot-serve-stack-layout.md)
for the rationale.

## Run it

```sh
bb prototype:serve          # http://localhost:7777/
```

## Architecture

- `server.bb` — Babashka + http-kit. Static files under `public/`. `/api/*`
  shells out to `knot <cmd> --json` and forwards the envelope verbatim. The
  same integration contract a future VSCode extension or Emacs xwidget would
  use.
- `public/index.html` — single page; `#ticket-stack` is the render target.
- `public/styles.css` — design tokens + chip system + `.stack` layout.
- `public/app.js` — vanilla JS, fetch then render. No framework, no build.

API surface used by the front-end:

| Route              | knot call                    |
|--------------------|------------------------------|
| `/api/ready`       | `knot ready --json`          |
| `/api/in-progress` | `knot list --status in_progress --json` |
| `/api/blocked`     | `knot blocked --json`        |
| `/api/show/:id`    | `knot show <id> --json`      |

## VSCode Webview shape

The Stack layout was picked partly because it survives a 380 px viewport
without degradation — that's the realistic VSCode side-panel size. To
eyeball: devtools → responsive mode → set width to ~380 px.

## What's next

This dir gets deleted when **kno-01krxmwemy05** lands — the productionisation
ticket promotes the prototype to a real `knot serve` command. Until then,
this is the runnable artifact for browser-side experimentation.

## Constraints honoured

- No AI attribution in commits (project + global rule).
- No hand-edits under `.tickets/` — all data via `knot --json`.
- No tests, no abstractions — prototype skill rules 1 and 3.
- Lives under `prototype/` (not `src/`), so `bb test` / `clj-kondo --lint
  src test` don't see it.
