# PRD: knot.el — Emacs mode for the knot CLI (v0.1)

## Problem Statement

I run knot from a terminal today. Every interaction — listing what's next, opening a ticket, flipping an acceptance criterion, adding a note, bumping priority, starting work — is a separate shell command typed in a separate window from where I read and edit code. The friction is mechanical (context-switching between Emacs and a terminal for many small reads and writes a day) and cognitive (the ticket I'm working on is rendered in plain `knot show` text whose ids aren't navigable, whose AC checkboxes aren't actionable, and whose deps require me to mentally cross-reference back to another `knot dep tree` invocation).

I want to interact with knot from inside Emacs the same way I interact with git: via a dedicated mode that knows the tool's shape, lets me discover commands without memorizing them, and renders the data in buffers I can navigate.

## Solution

A single-file Emacs package, `emacs/knot.el`, that fronts the knot CLI with a magit-style UI:

- Tabulated-list buffers for `list` / `ready` / `blocked` / `closed`, all sharing one project-scoped buffer with view-switching.
- A `markdown-view-mode`-derived show buffer per ticket, with buttonized ids that drill into other tickets and a keymap for in-place AC flipping.
- A dedicated deps-tree buffer rendered from `knot dep tree --json`.
- `transient` menus for every mutation surface (create, status transitions, priority/mode/tags/assignee/parent updates, deps and links, AC add/remove).
- Capture buffers in markdown-mode for long-form fields (description, design, body, notes), committed via stdin or `--flag value` to `knot update` / `knot add-note`.
- Project detection via `knot info --json`, cached per directory and captured as each buffer's `default-directory` — the same shape as `magit-toplevel`.
- All CLI calls go through one boundary function that runs the subprocess synchronously, parses the `--json` envelope, and signals `user-error` on `ok:false`.

The package lives in this monorepo because the CLI contract is still evolving and a `--json` shape change should move in lockstep with its primary Emacs consumer.

## User Stories

1. As Jonas, I want `M-x knot` from anywhere in Emacs to open a dispatch transient, so that I have a single discoverable entry point.
2. As Jonas, I want the dispatch transient to expose `list`, `ready`, `blocked`, `closed`, `create`, `quick-create`, `info`, and `refresh`, so that I never have to remember individual command names.
3. As Jonas, I want `?` inside any knot.el buffer to reopen the dispatch transient, so that discoverability is the same regardless of where I am.
4. As Jonas, I want the list buffer to render the same columns as `knot list` (id, title, type, priority, mode, status, optional AC progress), so that the view I get matches what I see in the terminal.
5. As Jonas, I want a single project-scoped list buffer that switches between list/ready/blocked/closed views in place (`l`/`r`/`b`/`c`), so that I'm not juggling four near-identical buffers.
6. As Jonas, I want the active view and active filters shown in the header line, so that I always know what slice of tickets I'm looking at.
7. As Jonas, I want to apply `--mode`, `--tag`, `--type`, `--status`, `--assignee`, `--limit`, and `--acceptance-complete` filters from a transient layered on the list view, so that the same filtering vocabulary works in Emacs.
8. As Jonas, I want `RET` on a list row to open that ticket's show buffer, so that drilling in is a single key.
9. As Jonas, I want the show buffer to render the ticket body in markdown so that section headings, lists, and code blocks fontify naturally.
10. As Jonas, I want every ticket id rendered in any buffer (deps, links, parent, body references) to be a button, so that `RET` on an id opens that ticket's show.
11. As Jonas, I want `quit-window` (`q`) to be the back button across all knot.el buffers, so that the standard Emacs navigation idiom works.
12. As Jonas, I want `]` and `[` in a show buffer to flip to the next/previous ticket in the originating list buffer, so that I can review a filtered batch without bouncing back to the list.
13. As Jonas, I want `RET` on an acceptance criterion line in the show buffer to flip its done/undone state via `knot update --ac "..." --done|--undone`, so that AC management feels first-class.
14. As Jonas, I want `+` (or `a`) in the AC section to prompt for a new criterion and add it via `--add-ac`, so that I can grow AC without leaving the buffer.
15. As Jonas, I want `-` (or `k`) on an AC line to remove it via `--remove-ac` after confirmation, so that AC pruning is one key plus confirmation.
16. As Jonas, I want `e` in the show buffer to pop a markdown-mode capture buffer prefilled with the current `## Description`, so that I can edit long-form prose with Emacs's full editing power.
17. As Jonas, I want `C-c C-c` in a capture buffer to commit the content via `knot update --description "..."` (or the field-specific flag), so that the commit path is the same regardless of which field I'm editing.
18. As Jonas, I want `C-c C-k` in a capture buffer to cancel without writing, so that I can bail out cleanly.
19. As Jonas, I want a generic capture mode used by description, design, body, and note buffers (parameterized by target field), so that the UX is consistent across long-form mutations.
20. As Jonas, I want `n` (add note) in the show buffer to pop a capture buffer that pipes its content via stdin to `knot add-note <id>`, so that note-taking is a single key.
21. As Jonas, I want capital `E` in the show buffer to shell out to `knot edit <id>` with `EDITOR=emacsclient` for full-file editing, so that I have an escape hatch when granular edits aren't enough.
22. As Jonas, I want a `,` (or similar) prefix in the show buffer to open an update transient with infix args for status, priority, mode, type, tags, assignee, and parent, so that atomic frontmatter mutations are one transient call with no buffer pop.
23. As Jonas, I want `s` in list or show to call `knot start <id>`, so that the most common transition has its own key.
24. As Jonas, I want `x` (close) in list or show to prompt for a closing summary in the minibuffer and call `knot close <id> --summary "..."`, so that closing a ticket is a single guided flow.
25. As Jonas, I want the close flow to surface the acceptance gate's `acceptance_incomplete` error from the JSON envelope as a `user-error` in the minibuffer, so that I learn why a close was blocked.
26. As Jonas, I want `c` to open a `knot-create` transient with infix args for type, priority, mode, tags, assignee, parent, deps, links, acceptance, and external-refs, with the title prompted in the minibuffer on commit, so that ticket creation reuses the same magit-style verb-with-options pattern.
27. As Jonas, I want create transient infix completions sourced from `knot info --json`'s `allowed_values` (statuses, types, modes, priority range) and `defaults`, so that the transient always reflects the current project's configured values.
28. As Jonas, I want post-create to drop me into the new ticket's show buffer with point parked on `## Description`, so that I can immediately write the body if I want.
29. As Jonas, I want capital `C` (quick-create) to prompt only for a title, run `knot create "title"` with all defaults, and drop me in show, so that low-friction capture is one key plus a title.
30. As Jonas, I want `D` in show or on a list row to open a deps transient with add/remove/tree-open actions, so that dep management has its own surface.
31. As Jonas, I want `L` in show or on a list row to open a links transient with add/remove actions, so that link management mirrors deps.
32. As Jonas, I want adding a dep or link to use `completing-read` against live tickets, displaying `id  title` as candidates, so that I can pick by title fragment.
33. As Jonas, I want unlink/undep candidate lists to include archived tickets, so that I can remove relationships to closed work.
34. As Jonas, I want `k` (or similar) on an existing dep or link id line in the show buffer to remove that relationship after confirmation, so that one-off cleanups don't require the transient.
35. As Jonas, I want a dedicated `*knot-deps: <project> · <id>*` buffer that renders `knot dep tree --json` as an indented outline with status glyphs (✓ for closed, ○ for live), so that the tree view is properly navigable rather than ASCII.
36. As Jonas, I want each node in the deps buffer to be a button that opens that ticket's show, so that tree exploration is uniform with the rest of the package.
37. As Jonas, I want an `f` toggle in the deps buffer to switch between collapsed (`knot dep tree`) and `--full` rendering, so that I can see expanded duplicate subtrees when I want them.
38. As Jonas, I want every mutating command to auto-refresh the buffer that issued it, so that the view always reflects the latest state without my pressing `g`.
39. As Jonas, I want every mutating command to also refresh any other knot.el buffer for the same project that's currently visible in a window, so that side-by-side views stay in sync.
40. As Jonas, I want `g` to be a manual refresh in any knot.el buffer, so that I can force a re-fetch when I think the state has changed externally.
41. As Jonas, I want knot.el to detect the project root by running `knot info --json` from `default-directory` and caching the result, so that project detection mirrors `magit-toplevel` exactly.
42. As Jonas, I want each knot.el buffer to capture its project root as a buffer-local `default-directory`, so that commands invoked from the buffer always run against the right project.
43. As Jonas, I want buffer names to be project-qualified (e.g. `*knot-list: Knot*`, `*knot-show: Knot · kno-01k...*`), so that multi-project workflows don't collide.
44. As Jonas, I want the cached info envelope to feed transient completion sources (statuses, types, modes, priority range) and defaults, so that one CLI call serves both project detection and UI metadata.
45. As Jonas, I want a JSON envelope returning `ok:false` to surface as a `user-error` in the minibuffer with the message from the envelope, so that errors are clear without a pop-up.
46. As Jonas, I want knot.el to check `data.project.knot_version` from `knot info` at first contact and warn (without refusing to load) if the CLI is older than knot.el's declared minimum, so that version drift in the monorepo is caught early.
47. As Jonas, I want every CLI subprocess to use `call-process` (sync) by default, so that the package stays simple while the CLI's ~75ms latency is well under the perceptual threshold.
48. As an agent reading the codebase, I want knot.el housed at `emacs/knot.el` in this repo, so that CLI contract changes and Emacs-mode updates move in the same commit.
49. As an agent reading the codebase, I want a `bb lint:elisp` task (or extended `bb lint`) that byte-compiles `emacs/knot.el` and runs `package-lint`, so that the same lint-before-commit discipline applies to the Emacs mode.
50. As Jonas, I want `knot.el` to require Emacs 28.1 (for built-in `transient`) and hard-require `markdown-mode`, so that the dependency story is minimal and explicit.

## Implementation Decisions

### Repository layout

knot.el lives at `emacs/knot.el` in this monorepo. The CLI contract and its primary Emacs consumer move in lockstep — a `--json` shape change and the matching knot.el patch land in one commit. Distribution-time, a MELPA recipe can target `:files ("emacs/knot.el")`.

### Anchor metaphor: magit-style

knot.el models tickets as opaque domain objects fronted by tabulated-list and `markdown-view-mode` buffers, with `transient` menus for every verb. No org-mode source representation. See [docs/adr/0001-knot-el-magit-style-ui.md](../adr/0001-knot-el-magit-style-ui.md).

### CLI invocation

Single boundary function `knot-cli-call`:

- Always invokes the knot binary with the trailing `--json` flag for any read-shaped command and any mutating command that supports it.
- Runs synchronously via `call-process` (or `call-process-region` when piping stdin).
- Parses the response with `json-parse-buffer` into a plist or alist.
- On `ok:false`, signals `user-error` with the envelope's error message.
- All other modules go through it. No direct subprocess calls elsewhere.

### Project detection

Mirrors `magit-toplevel`:

- `knot-info-current` runs `knot info --json` from `default-directory` on first call per directory, caches the full info envelope in a hash table keyed by directory.
- Each knot.el buffer captures the resolved project root as its buffer-local `default-directory`.
- The cached envelope feeds completion sources (`allowed_values.statuses`, `types`, `modes`, `priority_range`) and defaults (`defaults.default_type`, `default_priority`, `default_mode`).
- Multi-project works by construction: every command resolves from the current buffer's `default-directory`.

### Buffer architecture

- One `*knot-list: <project>*` buffer per project, reused across list/ready/blocked/closed views. Active view and filters shown in the header line. `l`/`r`/`b`/`c` switch view in place.
- One `*knot-show: <project> · <id>*` buffer per ticket. Revisiting the same id reuses the existing buffer.
- One `*knot-deps: <project> · <id>*` buffer per ticket-deps view. JSON-rendered indented outline with status glyphs.
- Capture buffers named `*knot-edit-<field>: <project> · <id>*` for description / design / body / note.

### Editing model

`knot update` + capture buffers as the primary path; `knot edit` as escape hatch. See [docs/adr/0002-knot-el-prefers-update-over-edit.md](../adr/0002-knot-el-prefers-update-over-edit.md).

Concretely:

- Atomic frontmatter mutations (title, type, status, priority, mode, assignee, parent, tags): single `knot update --flag value` call from a transient infix.
- Acceptance criteria flip: `knot update <id> --ac "title" --done|--undone` from `RET` on an AC line in show.
- AC add/remove: `--add-ac` / `--remove-ac` from `+` / `-` in the AC section.
- Long-form fields (description, design, body): pop a markdown-mode capture buffer prefilled with the current value; `C-c C-c` commits via `knot update --<field> "..."` passing the buffer contents as a single argv element (no shell escaping); `C-c C-k` cancels.
- Add note: pop a markdown-mode capture buffer; `C-c C-c` pipes content via stdin to `knot add-note <id>` using `call-process-region`.
- Escape hatch: capital `E` in show shells out to `knot edit <id>` with `EDITOR=emacsclient`.

### Refresh model

- Every mutating command calls `knot-refresh` on completion.
- `knot-refresh` re-fetches the current buffer's data and re-renders.
- A post-mutation walk over the window list refreshes any other knot.el buffer for the same project currently displayed in a window.
- Buried (live but undisplayed) buffers are not auto-refreshed. `g` is the manual refresh.
- No filewatch in MVP. External edits (from another agent or terminal session) surface only when the user issues a refresh.

### Drill-down navigation

- Ids in show buffers (deps, links, parent, free-text references in body) are buttonized via text properties + a local keymap.
- `RET` on an id opens that ticket's show, replacing the current window.
- `quit-window` (`q`) is the back button — relies on Emacs's existing window-history mechanics, not a custom stack.
- `]` and `[` in a show buffer move to the next/previous ticket in the originating list buffer when applicable (the show buffer captures the originating list buffer + position as buffer-locals when opened from a list).
- Drilling in from a dep/link/parent does not set the `]`/`[` stash.

### Modules

The implementation breaks into a small set of focused modules. Deep modules encapsulate behavior behind simple interfaces and are the testable core; shallow modules are UI glue.

**Deep:**

1. **`knot-cli`** — single CLI boundary. `(knot-cli-call ARGS &optional STDIN)` runs the subprocess, parses `--json`, returns the parsed envelope's `data` field on success, signals `user-error` on `ok:false`. Pure-ish (side effect: subprocess). Tested by mocking the binary path or running against a fixture project.

2. **`knot-info`** — project oracle. `(knot-info-current)` returns the cached info envelope for `default-directory`. `(knot-info-allowed-values FIELD)` and `(knot-info-defaults FIELD)` are completion-source lookups. `(knot-info-invalidate &optional ROOT)` clears the cache.

3. **`knot-id`** — id parsing & display. `(knot-id-at-point)`, `(knot-id-buttonize-region BEG END)`, `(knot-id-format ID TITLE)`. Used by every view that displays ids.

4. **`knot-format`** — per-value face mapping for status / priority / mode / type. One source of truth so list, show, deps tree colour consistently.

**Shallow:**

5. **`knot-list`** — `tabulated-list-mode` derivative; renders list/ready/blocked/closed with view-switching, filter state in header line.

6. **`knot-show`** — `markdown-view-mode` derivative; renders one ticket; AC-line keymap; launches capture buffers; carries originating-list buffer-locals for `]`/`[`.

7. **`knot-deps`** — JSON-rendered tree view with status glyphs; node buttons.

8. **`knot-create`** — create transient + quick-create command.

9. **`knot-update`** — per-field update transients (status, priority, mode, tags, assignee, parent, AC, deps, links).

10. **`knot-capture`** — generic capture-buffer minor mode; carries target id / field / post-commit callback as buffer-locals.

11. **`knot-dispatch`** — top-level `M-x knot` transient.

All in `emacs/knot.el` for MVP. Split into separate files when the single file crosses ~1500 lines.

### Naming & packaging

- `knot-` prefix on every symbol (functions, vars, faces, keymaps, customization group).
- Customization group `knot`.
- `;;; Commentary:` block at the head of the file.
- Package metadata: `Emacs "28.1"`, `markdown-mode "2.5"` (hard-require).
- License header matches the repo's existing license.
- Autoloads on `knot`, `knot-list`, `knot-create-quick`, `knot-status` (alias for the dispatch entry).
- No default global keybinding. Users bind `C-c k` (or similar) to `knot` themselves.

### Version compatibility

knot.el declares a minimum CLI version. On first `knot-info-current` call in a project, it compares the cached `project.knot_version` against the declared minimum and `lwarn`s if older. The mode still loads — refusing entirely would surprise users mid-task — but the warning surfaces in `*Warnings*`.

### Lint discipline

A new `bb lint:elisp` task (or extension of `bb lint`) byte-compiles `emacs/knot.el` and runs `package-lint` if installed. The repo's `AGENTS.md` hard rule "lint before commit" extends to elisp via this task.

## Testing Decisions

No automated tests in MVP. The behavioral guarantees live in the knot CLI, which is comprehensively tested; knot.el is UI glue around a stable contract.

When tests are added later, the deep modules (`knot-cli`, `knot-info`, `knot-id`, `knot-format`) are the candidates. They have simple interfaces and rare-changing shapes — the criteria for tests that pay off.

Test framework: ert (built-in). Buttercup is not pulled in unless a specific scenario calls for it.

What makes a good knot.el test: it asserts external behavior (what the user sees, what arguments the CLI receives) rather than internal state. CLI boundary tests use a stub `knot` script that records argv and emits canned JSON. View-rendering tests assert buffer contents and text properties after a known input, not the intermediate parse steps.

## Out of Scope

- **Filewatch / auto-refresh on external changes.** No `filenotify` subscription on `.tickets/`. External edits surface on next manual `g`. May be added in v0.2 if the absence becomes friction.
- **Org-capture template** for cross-Emacs ticket capture (`C-c c k` from anywhere). Possible later; not MVP.
- **Modeline indicator** of ready/blocked counts. Cute, not load-bearing.
- **`xref` integration** for ticket-id navigation (`M-.` push, `M-,` pop). `quit-window` history is enough for v0.1.
- **Folding sections in the deps tree** (`TAB` to collapse/expand). Flat render is fine; add folding when trees grow large enough to need it.
- **`prime` view** as a dedicated buffer. The dispatch transient and the existing list views cover the same ground.
- **MELPA submission.** v0.1 ships as monorepo file; MELPA paperwork happens when the package is stable and broadly useful.
- **`project.el` / `projectile` integration** for project root detection. Walk-up via `knot info` is sufficient; framework integration can be added as an opt-in later.
- **Cross-project search.** Each buffer is scoped to one project; no "search across all my knot projects" command.
- **Async CLI calls.** At ~75ms per call, sync is fine. Switch to `make-process` for any specific command that proves slow in practice.
- **Customizable column sets** for the list buffer. The columns match `knot list` by default; configurability is a v1+ concern.

## Further Notes

- **The monorepo decision is load-bearing.** Keeping knot.el in this repo means CLI contract changes can't drift from the Emacs consumer; both move in one commit. See [docs/adr/0001-knot-el-magit-style-ui.md](../adr/0001-knot-el-magit-style-ui.md) and [docs/adr/0002-knot-el-prefers-update-over-edit.md](../adr/0002-knot-el-prefers-update-over-edit.md) for the two surprising-without-context calls.

- **JSON is the contract, not the text tables.** The text output of `knot list` / `knot show` / `knot dep tree` is shaped for terminal humans (ANSI colors, truncation, conditional columns). knot.el parses `--json` exclusively. If JSON output of a command isn't yet implemented in the CLI, the right move is to add it on the CLI side rather than scrape text in elisp.

- **The transient ecosystem is the discoverability layer.** Every verb-with-options surface in the CLI maps to a transient; the dispatch transient is the entry point. Users should never need to memorize a binding — `?` always reopens the menu.

- **Capture buffers are the long-form bridge.** Anywhere a CLI flag accepts a string that could plausibly contain newlines (description, design, body, summary, note text), the UI shape is a markdown-mode buffer with `C-c C-c` to commit. The same `knot-capture` mode parameterizes all of them.

- **`knot info` is more than project detection.** It returns enums (`statuses`, `types`, `modes`, `priority_range`) and defaults that knot.el needs for completion and validation. One call serves both roles, and the cache keeps it free after the first invocation per directory.
