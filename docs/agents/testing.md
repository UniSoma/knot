# Running Tests

Run the suite with the Babashka task runner:

```bash
bb test
```

The task globs `test/**/*_test.clj` and runs every namespace it finds. There is no scoped subset — the suite is fast enough that every change runs the full set.

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
