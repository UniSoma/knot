# Running Tests

Run the suite with the Babashka task runner:

```bash
bb test
```

The task globs `test/**/*_test.clj` and runs every namespace it finds. There is no scoped subset — the suite is fast enough that every change runs the full set.

## The runner is parallel

`bb test` hands the namespaces to `script/knot/test_runner.clj`, which fans the individual test vars out over one worker per CPU. The suite is dominated by end-to-end tests that spawn a fresh `bb` per command — roughly 890 subprocesses at ~90ms each — so running them one at a time wasted almost all of the wall clock. Set `KNOT_TEST_THREADS` to override the worker count.

Fixtures, counters, and failure output behave exactly as `clojure.test/run-tests`: `:once` fixtures still wrap their whole namespace (and force that namespace to run its vars in order), `:each` fixtures still wrap every var, each var reports into its own counters ref, and failures are captured per var and replayed grouped by namespace.

**`with-redefs` rebinds a var process-wide, so it cannot run next to anything else.** The runner scans each test source for `with-redefs`, `alter-var-root`, and `System/setProperty`, and runs any namespace that uses one alone, in a serial phase that starts only once the parallel phase has fully drained. Introducing a different form of global mutation into a test means teaching `unsafe-ns?` about it — otherwise that test corrupts, or is corrupted by, whatever runs beside it.

Two blind spots in that scan are worth knowing. It reads only `*_test.clj` files, so a shared helper under `test/` that uses `with-redefs` is never seen. And it detects in-process mutation only: a test that shells out to a *mutating* command from the repo root (`help_test`'s `run-knot` passes no `:dir`, so it inherits the repo as cwd) would be classified safe while racing `schema_test`, which reads the real `.tickets/`. Today every such invocation is read-only. Drive mutating commands from a temp dir, as `integration_test` does.

## When the suite applies

`bb test` and `clj-kondo` gate commits that touch executable code: `src/`, `test/`, `bb.edn`, or CI workflows.

Documentation-only commits ship without either. Prose carries no coverage and no lint surface, so both tools report on a diff they cannot see: markdown files anywhere in the tree, tickets under `.tickets/`, ADRs under `docs/adr/`, `CONTEXT.md`, `AGENTS.md` / `CLAUDE.md`, and the bundled skills under `.claude/skills/`. A commit that mixes prose with code follows the code half — one touched `.clj` file puts the full suite back in play.

## Cross-platform considerations

`windows-latest` is a blocking CI gate alongside `ubuntu-latest` and `macos-latest`. Tests must pass on all three; a Windows-only failure blocks the merge.

**Anti-pattern: don't bake POSIX separators into path-shape assertions.** Checks like `(str/includes? path "/archive/")` or regexes like `#".+/\.tickets/.+"` look right on Linux/macOS but fail on Windows, where paths come back with `\` separators. The shape — not the structural claim — is what's wrong.

**Use `fs/components` for structural path claims.** When you want to assert "this path lives under a directory called `archive`", compare path *segments*, not substrings of the rendered string:

```clojure
(is (some #{"archive"} (map str (fs/components path))))
```

`fs/components` returns `java.nio.file.Path` segments; `(map str ...)` coerces them so `#{"archive"}` matches. The check is platform-independent because separators never enter the comparison. To pin "parent is `<dir>`" strictly (rejecting siblings), use the explicit-separator form: `(str (fs/path root subdir) java.io.File/separator)` as the prefix.

**`--json` envelopes are POSIX-normalized; stdout paths stay native.** Any path field emitted inside a `--json` envelope flows through `babashka.fs/unixify` so the wire shape is stable across OSes — JSON consumers shouldn't have to branch on `os.name`. Stdout paths are deliberately native: humans copy-paste them into the local shell, where they need to round-trip through the OS-native separator. Tests that assert against `--json` paths can use plain string comparisons; tests against stdout paths must use `fs/components` or equivalent structural checks.

## Test file conventions

- File suffix: `_test.clj` (anything else is ignored by the glob).
- Namespace mirrors the directory layout under `test/` (e.g. `test/knot/store_test.clj` → `knot.store-test`).
