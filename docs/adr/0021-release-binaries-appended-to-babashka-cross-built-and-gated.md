# Release binaries are uberjars appended to upstream babashka, cross-built and gated

Each release asset is a **Release binary**: the knot uberjar appended to the upstream babashka executable for one OS/arch. One Linux job builds all of them by downloading every babashka babashka publishes (linux amd64/aarch64 static, macos amd64/aarch64, windows amd64) at the version pinned in `.bb-version` and checking each against upstream's sha256. The tag-triggered **Release gate** smoke-tests each binary on its own platform, plus the bbin install, and only then creates the GitHub Release. This supersedes [0004](0004-release-tag-smoke-detects-post-push-not-gate.md).

Assets are named without a version (`knot-<os>-<arch>.tar.gz`, `knot-windows-amd64.zip`, `SHA256SUMS`), so `releases/latest/download/<asset>` is a stable URL for the README and install scripts. These names are a contract: a future `knot upgrade` and every published installer depend on them.

## Considered options

- **GraalVM native-image or jolt** — rejected. The jolt build worked (18.6 MB) but was 2–4x slower than bb on every command, because its pure-Clojure YAML shim is ~7x slower than SnakeYAML. The appended binary is ~68 MB and behaves exactly like `bb -m knot.main`. Size is the only loss.
- **Build on each OS from its installed bb** — rejected. Each runner needed `.exe` naming, chmod and `BB_BIN` handling, and Linux needed the static bb to avoid a glibc floor. Appending is byte-level, so one job can build every target, and downloading upstream archives guarantees the right runtime per target.
- **Attach assets on `release: published`, with smoke as detection only (0004)** — rejected. With binaries as the primary install path, a Release without assets or with a broken asset is a broken release. Making CI the publisher removes the race between release creation and upload. The "tag pushed, no Release yet" state that 0004 avoided is what a gate is supposed to look like.
- **Versioned asset names** — rejected. They make "latest" URLs impossible without an API call.

## Consequences

- A failed gate leaves a tag on origin with no Release. Recovery: delete the tag locally and remotely, fix, and cut again. A `workflow_dispatch` re-run of the publish job covers a publish failure after smoke passes.
- Builds are not byte-reproducible (jar timestamps), so the gate builds once and the smoke and publish jobs reuse that output.
- `ci.yml` reads `.bb-version`, so the tests run on the babashka that ships.
- The binaries are unsigned. Install scripts are unaffected, but browser downloads need `xattr -d com.apple.quarantine` on macOS or `Unblock-File` on Windows.
