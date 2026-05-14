# Knot — File-Based Issue Tracker

A Babashka file-based ticket tracker, storing tickets as markdown with YAML frontmatter for AI-friendly, daemon-less workflows.

## Hard rules (every task)

- **No AI attribution in commit messages or trailers.** No "Generated with", `Co-Authored-By: Claude`, AI emojis, or similar.
- **Map `.clj` files before reading them.** Run `clj-surgeon :op :ls :file <path>` from the shell (it's a CLI tool with EDN-pair args, *not* a Clojure ns — don't `(require)` it) first on any `.clj` file over ~500 lines, then `Read` only the line ranges you need. ~150× more token-efficient than blind reads. Full op reference: the `clj-surgeon` skill.
- **Prefer nREPL for evaluation.** `clj-nrepl-eval -p 7888 '<form>'` over `bb -cp src -e '<form>'` for sanity checks and exploration — persistent session (state survives between calls), no JVM cold-start, `:reload`-aware. See [docs/agents/clojure-repl-evaluation.md](docs/agents/clojure-repl-evaluation.md).
- **Test before commit.** Run `bb test`. See [docs/agents/testing.md](docs/agents/testing.md).
- **Lint before commit.** Run `clj-kondo --lint src test`. See [docs/agents/linting-and-formatting.md](docs/agents/linting-and-formatting.md).
- **Keep `.claude/skills/knot/SKILL.md` in sync with the CLI.** This repo dogfoods knot, so the bundled skill is both downstream documentation and the contract every future agent loads. When you add/remove a command, change a flag, or change a JSON shape, update the skill in the same commit. A drifted skill silently misleads every agent that loads it.

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
