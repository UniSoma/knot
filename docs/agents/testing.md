# Running Tests

Run the suite with the Babashka task runner:

```bash
bb test
```

The task globs `test/**/*_test.clj` and runs every namespace it finds. There is no scoped subset — the suite is fast enough that every change runs the full set.

## Test file conventions

- File suffix: `_test.clj` (anything else is ignored by the glob).
- Namespace mirrors the directory layout under `test/` (e.g. `test/knot/store_test.clj` → `knot.store-test`).
