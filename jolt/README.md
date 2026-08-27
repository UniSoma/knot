# Standalone binary via jolt

[jolt](https://github.com/jolt-lang/jolt) compiles Clojure to a native
executable on Chez Scheme. This directory builds `knot` as a single binary
that needs neither babashka nor a JVM.

```
bb build:jolt          # or: cd jolt && jolt build --opt --tree-shake -m knot.main -o knot
./jolt/knot ready
```

Install jolt with `curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash`.

## What is in here

- `deps.edn` — the dependency set knot needs outside babashka. `:paths` reaches
  back into `../src` and `../resources`, so the binary is built from the same
  source as the bb entry point.
- `shim/cheshire/core.clj` — `cheshire.core` over `clojure.data.json`.
  Real cheshire wraps Jackson, which jolt cannot run.
- `shim/clj_yaml/core.clj` — `clj-yaml.core` for the frontmatter subset knot
  reads and writes. Real clj-yaml wraps SnakeYAML. The emitter reproduces
  SnakeYAML's block style so ticket files round-trip byte for byte between
  the two builds.

## Known gaps

- `knot serve` does not run: http-kit is a Java library, and `MessageDigest`
  needs jolt's separate crypto library. Every other command passes the full
  `bb test` suite when the integration tests are pointed at the binary.
- It is slower than babashka. `bb build:bb` makes the other kind of standalone
  binary — knot's uberjar appended to the `bb` executable — and against 149
  tickets it runs `--help` in 80 ms, `list`/`check`/`create` in 100–120 ms
  and `show` in 115 ms at 100 MB peak RSS; the jolt binary takes 155 ms,
  300–350 ms and 440 ms at 160 MB. The gap is the YAML shim parsing tickets
  in Clojure rather than SnakeYAML. Jolt wins only on size: 18 MB vs 68 MB.
- Three jolt divergences shaped the source: `clojure.string/last-index-of`
  rejects a char argument, `Matcher.find(int)` ignores its start offset, and
  the vendored `babashka.fs/list-dir` is unbound. Knot avoids all three.
