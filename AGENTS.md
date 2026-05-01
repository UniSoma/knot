# Knot — File-Based Issue Tracker

A Babashka file-based ticket tracker, storing tickets as markdown with YAML frontmatter for AI-friendly, daemon-less workflows.

## Hard rules (every task)

- **No AI attribution in commit messages or trailers.** No "Generated with", `Co-Authored-By: Claude`, AI emojis, or similar.
- **Map `.clj` files before reading them.** Run `clj-surgeon :op :ls :file <path>` first on any `.clj` file over ~500 lines, then `Read` only the line ranges you need. ~150× more token-efficient than blind reads. Full op reference: the `clj-surgeon` skill.
- **Test before commit.** Run `bb test`. See [docs/agents/testing.md](docs/agents/testing.md).
- **Lint before commit.** Run `clj-kondo --lint src test`. See [docs/agents/linting-and-formatting.md](docs/agents/linting-and-formatting.md).

## Where to look

| Topic                    | Source                                                                           |
|--------------------------|----------------------------------------------------------------------------------|
| Git & commit conventions | [docs/agents/git-and-commits.md](docs/agents/git-and-commits.md)                 |
| Running tests            | [docs/agents/testing.md](docs/agents/testing.md)                                 |
| Linting & formatting     | [docs/agents/linting-and-formatting.md](docs/agents/linting-and-formatting.md)   |
| Clojure REPL evaluation  | [docs/agents/clojure-repl-evaluation.md](docs/agents/clojure-repl-evaluation.md) |
| Issue tracking (knot)    | [docs/agents/issue-tracking.md](docs/agents/issue-tracking.md)                   |
