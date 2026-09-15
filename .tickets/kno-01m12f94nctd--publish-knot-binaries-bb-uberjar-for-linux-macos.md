---
id: kno-01m12f94nctd
title: Publish knot release binaries for every babashka platform through a gated release workflow
status: in_progress
type: task
priority: 2
mode: hitl
created: '2026-08-27T20:41:14.112974689Z'
updated: '2026-09-15T19:26:35.797065094Z'
tags:
- distribution
- release
- ci
acceptance:
- title: bb build:release --target all on Linux writes the 5 platform archives and SHA256SUMS from upstream babashka at the .bb-version pin, each download verified against upstream sha256; --target host builds a runnable binary whose --version matches knot.version; build:bb is removed
  done: true
- title: ci.yml and the gate workflow read the babashka version from .bb-version
  done: true
- title: install.sh and install.ps1 install a chosen or latest release from KNOT_RELEASE_URL, verify SHA256SUMS, and honour KNOT_VERSION and KNOT_INSTALL_DIR
  done: false
- title: Each of the 5 smoke legs installs its binary via the installer from a local HTTP server and passes --version, --help, help topics and the init/create/ls/show/start/close/check golden path; the bbin legs pass; release-smoke.yml is deleted
  done: false
- title: A workflow_dispatch dry_run on main is green across every leg before the release that ships this change
  done: false
- title: A pushed vX.Y.Z tag produces a GitHub Release with the 5 archives and SHA256SUMS and notes from the tag, created only after every smoke leg passes
  done: false
- title: README Install covers installer, manual download with quarantine note, and bbin; /release Step 9, Step 11 and the upgrade-path template are updated
  done: true
- title: ADR 0021 is committed and ADR 0004 is marked superseded by it
  done: true
links:
- kno-01kqcpb0t5s7
- kno-01kqzh3jgwf0
---

## Description

Users without babashka/bbin have no way to get knot. `README.md` documents bbin as the only install path. `bb build:bb` builds only for the host (no `.exe`, a POSIX chmod that throws on Windows, `BB_BIN` ignored by the uberjar step), and nothing publishes a binary. `.github/workflows/release-smoke.yml` smoke-tests the bbin shim after a tag push but produces no artifacts. `/release` Step 11 creates a GitHub Release that has notes and no assets.

The goal: every tagged release ships a Release binary for each platform babashka publishes, an installer one-liner per OS, and a Release gate that keeps a broken binary or installer from being published. Decision record: ADR 0021, which supersedes ADR 0004. Glossary: **Release binary**, **Release gate** in CONTEXT.md.

## Design

### Build

- **Build task:** `script/` gains a build namespace, run as `bb build:release [--target all|host|<platform>]`. It replaces `build:bb`.
- **Uberjar:** built once with `bb uberjar … -m knot.main`. `bb.edn` `:paths` is already `src`+`resources` with no `test/`, so no classpath override is needed.
- **Babashka pin:** `.bb-version` is the single pin. The build script, `ci.yml` and the gate workflow all read it.
- **Per target:** for linux-amd64, linux-aarch64 (upstream `-static` builds), macos-amd64, macos-aarch64 and windows-amd64:
  1. Download the upstream babashka archive and check it against upstream `.sha256`.
  2. Extract `bb`/`bb.exe` and stream bb bytes followed by jar bytes into `knot`/`knot.exe`.
  3. Pack with `tar -czf` on Unix (keeps the executable bit) or zip on Windows.
- **Outputs:** `knot-{linux,macos}-{amd64,aarch64}.tar.gz`, `knot-windows-amd64.zip` and `SHA256SUMS` (`hash  filename`). Asset names are versionless and are a contract.

### Release gate workflow

- **Triggers:** a push of a tag matching `v[0-9]+.[0-9]+.[0-9]+`. `workflow_dispatch` takes `dry_run` (build and smoke, no publish) and a re-publish input for an existing tag.
- **Build job:** one `ubuntu-latest` job runs `--target all`, checks that the host binary `--version` equals the tag, and uploads `dist/`. Later jobs reuse that upload, since builds are not byte-reproducible.
- **Smoke jobs:** a matrix with `fail-fast: false` on `ubuntu-latest`, `ubuntu-24.04-arm`, `macos-15-intel`, `macos-latest` and `windows-latest`. Each leg:
  - serves `dist/` from `python3 -m http.server` and sets `KNOT_RELEASE_URL=http://127.0.0.1:<port>` (`file://` fails in pwsh `Invoke-WebRequest`)
  - runs `install.sh` or `install.ps1` against that server, which checks `SHA256SUMS`
  - checks that `--version` equals the tag, `--help` exits 0 and `knot help topics` is non-empty (resources are in the jar)
  - runs the `init/create/ls/show/start/close/check` golden path in a temp dir
- **bbin legs:** the bbin install smoke from `release-smoke.yml` moves into the gate, and that file is deleted.
- **Publish job:** runs `needs: [build, smoke]` and skips on dry run. It runs `gh release create v<ver> <assets> SHA256SUMS --notes-from-tag` and skips if the release exists.
- **Failed gate:** the tag stays and no Release exists. Retract the tag, fix, and cut again.

### Install scripts (served from raw `main`)

- **`install.sh`:** picks the asset from `uname -s`/`uname -m`. On macOS under Rosetta, `sysctl hw.optional.arm64` forces the arm64 build. It downloads the archive and `SHA256SUMS` to a temp dir and matches the exact filename with `sha256sum` or `shasum -a 256`. It extracts to `~/.local/bin/knot` and only warns if that is not on PATH. The whole body is a function called on the last line.
- **`install.ps1`:** installs to `%LOCALAPPDATA%\Programs\knot\knot.exe` and verifies with `Get-FileHash`. It adds the directory to the user PATH. It silences `$ProgressPreference` and restores it in `finally`.
- **Environment variables:** `KNOT_VERSION` (default latest), `KNOT_INSTALL_DIR` and `KNOT_RELEASE_URL`.

### Docs and `/release`

- **README Install:**
  1. Prebuilt binary: the installer one-liners and pinning with `KNOT_VERSION`.
  2. Manual download: verify `SHA256SUMS`, then clear the quarantine flag with `xattr -d com.apple.quarantine` on macOS or `Unblock-File` on Windows.
  3. With babashka: bbin, with the "requires babashka 1.3.0" note moved here.
  - The intro stops saying "distributed as a babashka script".
- **`/release` Step 9 (pre-push smoke):** runs `bb build:release --target host` and checks that binary.
- **`/release` Step 11:** `gh run watch` the gate, then confirm the release has 5 archives and `SHA256SUMS`.
- **Release-notes template:** the "Upgrade path" becomes re-running the installer, with bbin as a second line.

### Out of scope

macOS code-signing and notarization, Windows signing, `knot upgrade` and the update check (kno-01kqcpb0t5s7), scoop/brew/winget, and `jolt/`/`build:jolt` (left untouched).

## Notes

**2026-09-14T13:40:21.141925928Z**

Babashka 1.13.220 split the Linux bb into a dynamic build (needs glibc >= 2.28) and a static one. build:bb appends whichever bb it finds, so the linux-amd64 release asset must be built from the static bb to stay portable. setup-clojure installs linux-*-static today; if BB_BIN or another install path is used, pin the -static asset. CI moved to BB_VERSION 1.13.222.
