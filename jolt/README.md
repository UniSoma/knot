# Standalone binary via jolt

[jolt](https://github.com/jolt-lang/jolt) compiles Clojure to a native
executable on Chez Scheme. This directory builds `knot` as a single binary
that needs neither babashka nor a JVM.

```
bb build:jolt          # or: cd jolt && jolt build --opt --tree-shake -m knot.main -o knot
./jolt/knot ready
```

Install jolt with `curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash`.
Last verified against jolt v0.8.5 with `jolt-lang/time` pinned at v0.0.9. Since
jolt 0.8.1 a host class is provided by the library that declares it, so a time
pin older than v0.0.8 fails the build with `No dependency provides
java.time.format.DateTimeFormatter`; bump the sha in `deps.edn` rather than
requiring `jolt.time` by hand.

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
  binary — knot's uberjar appended to the `bb` executable — and against 158
  tickets it runs `--help` in 85 ms, `list`/`check` in 100–110 ms and `show`
  in 125 ms at 115–125 MB peak RSS; the jolt binary takes 130 ms, 210–255 ms
  and 275 ms at 190 MB. The gap is the YAML shim parsing tickets in Clojure
  rather than SnakeYAML. Jolt wins only on size: 18 MB vs 90 MB.
- `--tree-shake` is requested but skipped. `knot.serve/start-server!` reaches
  http-kit through `resolve` so the bb build does not load it at startup, and
  `flatland.ordered.set` does the same internally; either runtime lookup makes
  jolt keep the whole compiler image.
  `--boot small` produced a binary of the same size.
- Two jolt divergences shaped the source: `clojure.string/last-index-of`
  rejects a char argument and `Matcher.find(int)` ignores its start offset.
  Knot avoids both. A third, the vendored `babashka.fs/list-dir` being
  unbound, was fixed in jolt 0.8.5.
