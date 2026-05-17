# Release-tag smoke detects post-push, not as a release gate

The release-tag smoke workflow (`.github/workflows/release-smoke.yml`) fires on `v*` tag push and runs the installed shim across ubuntu/macos/windows. It is *detection*, not *prevention*: the tag stays on origin regardless of smoke outcome, and the GitHub Release publication (`gh release create` in `/release` Step 11) is not gated on smoke status. Prevention is the job of pre-push smoke (`/release` Step 9, single-platform local) and `bb test` (`ci.yml`, pre-merge).

## Considered options

- **Gate the GH Release on smoke green** — rejected for v0.5. Couples `/release` Step 11 to CI (Step 11 polls, or CI publishes the Release on its own success). Introduces an intermediate "tag pushed, Release not yet" state that needs explicit recovery handling. The detection model is recoverable with `git tag -d` + force-push retract; gate-the-release adds a state machine without changing the underlying retract path.
- **PR-time smoke (path-filtered on bb.edn / version.clj / workflows)** — deferred. Install-affecting paths change rarely, so the cost is bounded, but adds CI complexity before tag-time smoke has caught anything real. Revisit once tag-time smoke has earned its keep.
- **Pre-push smoke alone, no CI smoke** — rejected. Single-platform; misses "works on Linux, broken on macOS or Windows". The point of adding CI smoke is multi-platform reproducibility on top of the local pre-push check.

## Consequences

- `/release` Step 10 pushes the tag (triggering smoke async); Step 11 runs `gh release create` without waiting. A red smoke run can therefore land alongside a published Release. Retract path: `git tag -d v<X.Y.Z> && git push origin :refs/tags/v<X.Y.Z> && gh release delete v<X.Y.Z>`, then cut from a fix commit. Same as cutting any release — no special procedure.
- The maintainer can serialise manually by waiting between Steps 10 and 11 for the smoke run to complete. `/release` does not require this — the cost (red smoke published as latest) is acceptable for now.
- Revisiting as gate-release is contained: the change set is `release-smoke.yml` (publish on green), `/release` Step 11 (drop direct `gh release create`), and an ADR superseding this one.
