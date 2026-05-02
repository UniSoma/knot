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
| Issue tracking (knot)    | [docs/agents/issue-tracking.md](docs/agents/issue-tracking.md)                   |
