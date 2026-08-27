---
id: kno-01m12f94nctd
title: Publish knot binaries (bb + uberjar) for Linux, macOS and Windows on GitHub Releases
status: open
type: task
priority: 2
mode: hitl
created: '2026-08-27T20:41:14.112974689Z'
updated: '2026-08-27T20:41:14.112974689Z'
tags:
- distribution
- release
- ci
acceptance:
- title: bb build:bb produces target/knot.exe on Windows and target/knot elsewhere, honours BB_BIN for both the uberjar and the appended runtime, and --version matches knot.version
  done: false
- title: A published v* release gets knot-<ver>-linux-amd64.tar.gz, knot-<ver>-macos-arm64.tar.gz, knot-<ver>-windows-amd64.zip and a checksums file uploaded by CI
  done: false
- title: Each uploaded binary runs knot --version and the init/create/ls/close/check golden path on its platform in the workflow
  done: false
- title: README Install documents downloading a release binary and /release verifies the assets after Step 11
  done: false
links:
- kno-01kqcpb0t5s7
- kno-01kqzh3jgwf0
---

## Description

`bb build:bb` (`bb.edn:59-71`) produces a standalone `target/knot` by running `bb uberjar` and appending the jar to the local `bb` executable. It only builds for the host platform — `fs/set-posix-file-permissions` (`bb.edn:69`) throws on Windows and the output lacks `.exe` — and `bb.edn:65` hardcodes `bb` for the uberjar step while the surrounding lines honour `BB_BIN`, so an override would append a different runtime than the one that built the jar.

Nothing publishes the result. `.github/workflows/release-smoke.yml` runs the installed-shim smoke on a `ubuntu/macos/windows` matrix with `BB_VERSION: 1.12.218` but produces no artifacts; `/release` Step 11 (`.claude/commands/release.md:218`) already creates a GitHub Release with `gh release create` but attaches notes only. `README.md:28-51` documents bbin as the sole install path, with babashka as a hard prerequisite.

Users without babashka/bbin have no way to get knot. The goal is a downloadable binary per platform attached to every tagged release, so download + `chmod +x` (or unzip on Windows) is an install. Performance is a wash against plain bb (see the 2026-08-27 notes on kno-01kqcpb0t5s7).

## Design

A new workflow triggered on `release: published`, so it runs after `/release` Step 11 has created the release and avoids the race a `push: tags` trigger has with a not-yet-existing release. One job per OS on the same 3-platform matrix and pinned `BB_VERSION` as release-smoke, so the bundled runtime is the version CI already tests.

Each job runs `bb build:bb`, verifies the result (`--version` equals the tag, `--help` exits 0, and the `init/create/ls/close/check` golden path passes in a temp dir), packages it as `knot-<version>-<os>-<arch>.tar.gz` (`.zip` containing `knot.exe` on Windows) alongside a sha256 checksums file, and uploads with `gh release upload --clobber`.

`build:bb` learns to emit `knot.exe` when the located bb is `bb.exe`, skips the POSIX chmod there, and uses `BB_BIN` for the uberjar step too. README `Install` gains a "Prebuilt binaries" subsection above the bbin path; `/release` gets a post-Step-11 check that the assets appeared.

Out of scope: macOS code-signing/notarization, arm64 Linux, the jolt binary.
