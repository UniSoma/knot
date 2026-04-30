## Issue tracking

This project tracks work with **knot** — markdown tickets under `.tickets/` (closed auto-archive to
`.tickets/archive/`), config in `.knot.edn` at the repo root.

For any ticket-shaped intent ("what's next", "track this", "show me <id>", "I'm done", "blocked on X", "any open
bugs?"), use the `knot` skill — it's the canonical reference. The CLI keeps frontmatter, the dep graph, and the archive
consistent, and resolves partial IDs across live + archive.

**Never read or write `.tickets/` by hand.** No `Read`, `cat`, `grep`, `Write`, `Edit`, `sed`, or `mv` against files in
there — use `knot show` / `knot list --json` / `knot create` / `knot add-note` / `knot close` instead. If a knot command
behaves unexpectedly, surface the bug; don't bypass.

Hosted-tracker prefixes (`GH-`, `ENG-`, `LIN-`, `JIRA-`) point at *other* trackers — use the matching tool, not knot.
