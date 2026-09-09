# Knot — File-Based Issue Tracker

A Babashka file-based ticket tracker, storing tickets as markdown with YAML frontmatter for AI-friendly, daemon-less workflows.

## Hard rules (every task)

- **No AI attribution in commit messages or trailers.** No "Generated with", `Co-Authored-By: Claude`, AI emojis, or similar.
- **Read `.clj` files through `clj-surgeon`.** It replaces `Read`, `grep`, `sed`, and `cat` on any `.clj`/`.cljs`/`.cljc` file: `clj-surgeon :op :cat :file <path> :form <name>` when you know an owner, `:ls` to outline one you know nothing about. It's a shell CLI taking EDN-pair args, *not* a Clojure ns — never `(require)` it at the REPL. Read routing, refusals, and structural edits: the `clj-surgeon` skill.
- **Prefer nREPL for evaluation.** `clj-nrepl-eval -p 7888 '<form>'` over `bb -cp src -e '<form>'` for sanity checks and exploration — persistent session (state survives between calls), no JVM cold-start, `:reload`-aware. See [docs/agents/clojure-repl-evaluation.md](docs/agents/clojure-repl-evaluation.md).
- **Test before commit.** Run `bb test`. See [docs/agents/testing.md](docs/agents/testing.md).
- **Lint before commit.** Run `clj-kondo --lint src test`. See [docs/agents/linting-and-formatting.md](docs/agents/linting-and-formatting.md).
- **Place agent-facing material on exactly one of the three context surfaces.** Knot reaches a client project through *pull* (`knot --help` and `knot help <topic>`, fetched on demand from the installed CLI, authoritative for anything derivable from it), *push* (`knot prime`, always loaded — only what a cold agent can't recover, plus live state), and *pointer* (the bundled skill, the same guides held resident once its description fires). The pointer's source is `resources/knot/skill/`: edit there, then run `knot skill install` to regenerate the committed copy at `.claude/skills/knot/`, which `bb test` compares against the source. Never inventory commands, flags or topics in prose; a per-command caveat goes in that command's help `:notes`. When you add/remove a command, change a flag, or change a JSON shape, the surfaces move in the same commit. See [ADR 0017](docs/adr/0017-three-context-surfaces-pull-push-pointer.md) and [ADR 0019](docs/adr/0019-cli-ships-the-pointer-help-topics-and-skill-install.md).

## Where to look

| Topic                    | Source                                                                           |
|--------------------------|----------------------------------------------------------------------------------|
| Git & commit conventions | [docs/agents/git-and-commits.md](docs/agents/git-and-commits.md)                 |
| Running tests            | [docs/agents/testing.md](docs/agents/testing.md)                                 |
| Linting & formatting     | [docs/agents/linting-and-formatting.md](docs/agents/linting-and-formatting.md)   |
| Clojure REPL evaluation  | [docs/agents/clojure-repl-evaluation.md](docs/agents/clojure-repl-evaluation.md) |
| Issue tracking (knot)    | [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)                     |

## Agent skills

### Issue tracker

Issues live in this repo as markdown under `.tickets/`, managed exclusively via the `knot` CLI. See [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md).

### Triage labels

Canonical triage roles map to knot tags + modes (`triage` tag, `needs-info` tag, `afk`/`hitl` mode, close-with-`Won't do:` summary). See [docs/agents/triage-labels.md](docs/agents/triage-labels.md).

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root. See [docs/agents/domain.md](docs/agents/domain.md).

# Behavioral guidelines

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
