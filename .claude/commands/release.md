---
description: Cut a new release with version bump, changelog, commit, and tag
allowed-tools:
  - Read
  - Edit
  - Bash
  - Glob
  - Grep
  - Skill
---

# Release Workflow

Cut a new semantic version release for knot.

## Context

<version-file>
The version is defined in `src/knot/version.clj`:

```clojure
(def version "X.Y.Z")
```

Single source of truth — surfaced via `knot --version` and the `knot --help`
banner.
</version-file>

<changelog>
`CHANGELOG.md` follows Keep a Changelog format. The file carries a rolling
`[Unreleased]` section at the top (below `## Versioning`) where in-flight
changes are written during the cycle. Cutting a release renames that section
to the new version and seeds a fresh empty `[Unreleased]` above it.
</changelog>

<git-tags>
Tags use `vX.Y.Z` format (e.g. `v0.5.0`). Per-slice tickets and the release
coordination ticket carry the same `vX.Y.Z` tag for coverage-audit
reconciliation.
</git-tags>

<release-notes>
Each cut drafts release-notes prose to `release-notes-vX.Y.Z.txt` at repo
root (gitignored via `release-notes-v*.txt`). The same prose feeds both the
annotated git tag message (Step 8) and the GitHub Release body (Step 11) —
single source.
</release-notes>

## Procedure

### Step 1: Gather context

```bash
# Current version
grep 'def version' src/knot/version.clj

# Latest tag — if this command fails, this is the first release of the repo.
# In that case, run `git log --oneline` instead and reason about full history.
git describe --tags --abbrev=0

# Commits since last tag (only if a prior tag exists)
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

### Step 2: Determine new version

Based on commits since last tag, determine the bump:

- **patch** (X.Y.Z+1): bug fixes, minor improvements
- **minor** (X.Y+1.0): new features, non-breaking changes
- **major** (X+1.0.0): breaking changes

Ask the user to confirm if unclear.

### Step 3: Update version

Edit `src/knot/version.clj` to the new version.

### Step 4: Coverage audit (before CHANGELOG rename)

Cross-check two sources against the current `[Unreleased]` bullet list:

```bash
# Intent (ticket discipline)
knot list --tag vX.Y.Z --status closed

# Ground truth (every commit that landed)
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

Before writing any bullet, call the Skill tool with "writing-for-humans".
Every bullet is exposition register; the same standard governs the release
notes in Step 6, so one call covers both.

For each closed ticket and each commit, verify a corresponding bullet exists
under `[Unreleased]`. Backfill any missing entries **while the section is
still `[Unreleased]`** — once it's renamed in Step 5, the section is dated
and effectively sealed. Audit the existing bullets too: they were written
mid-cycle, before the section was reread as a whole.

The `git log` cross-check is the safety net: it catches slices that landed
without the `vX.Y.Z` ticket tag.

### Step 5: Rename `[Unreleased]` to the versioned section

In `CHANGELOG.md`:

1. Rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`.
2. Insert a fresh empty `## [Unreleased]` skeleton above it:

   ```markdown
   ## [Unreleased]

   ### Added/Changed/Fixed/Removed
   ```

If `[Unreleased]` was empty or missing entirely, write the new versioned
section directly below the `## Versioning` block instead.

Categorize entries:

- **Added**: new features
- **Changed**: changes to existing functionality
- **Fixed**: bug fixes
- **Removed**: removed features

### Step 6: Draft release-notes prose

Write to `release-notes-vX.Y.Z.txt` at repo root (gitignored). This single
file feeds both the annotated tag message (Step 8) and the GitHub Release
body (Step 11). Exposition register, under the writing-for-humans skill
loaded in Step 4; the file is done when its mechanical scan passes.

Canonical structure:

~~~
Release vX.Y.Z: <one-line theme>

<1-3 sentence lead: breaking? headline change?>

## Highlights

- **<phrase>.** <description>

## Breaking changes        # omit entire section for non-breaking releases

1. **<title>.** <description>

   Migration: <command or one-liner>

## Upgrade path

```sh
curl -fsSL https://raw.githubusercontent.com/UniSoma/knot/main/install.sh | sh
# Windows: irm https://raw.githubusercontent.com/UniSoma/knot/main/install.ps1 | iex
# bbin: bbin install https://github.com/UniSoma/knot.git
<per-release verification>
```

## Known follow-ups        # optional — omit when empty

- <ticket id>: <one-liner>
~~~

### Step 7: Commit release preparation

```bash
git add -A
git commit -m "Release vX.Y.Z"
```

Verb-leading commit convention used throughout this repo — no Conventional
Commits prefix. The prose file is excluded from staging by the
`release-notes-v*.txt` gitignore pattern.

### Step 8: Create annotated tag

```bash
git tag -a vX.Y.Z -F release-notes-vX.Y.Z.txt --cleanup=verbatim
```

Annotated (not lightweight) so `git show vX.Y.Z` carries the release prose,
and the same prose feeds the GitHub Release body in Step 11.
`--cleanup=verbatim` is load-bearing: git's default cleanup mode strips lines
beginning with `#` as comments, which would silently delete Markdown headings
(`## Highlights`, `## Upgrade path`, …) from the prose. Verify after tagging:

```bash
git cat-file -p vX.Y.Z | grep -c '^## '   # should match the prose's `##` count
```

### Step 9: Pre-push smoke

Build the host release binary and verify it starts and reports the new
version. `info`/`check` are deliberately out of scope here — they'd either
need a knot project or risk false-positive failures against this repo's own
tickets. Every platform, the installers and the golden path are covered by
the release gate (`.github/workflows/release.yml`) after push.

```bash
bb build:release --target host
knot=$(ls target/release/*/knot)
test "$("$knot" --version)" = "X.Y.Z"   # exact version string
"$knot" --help > /dev/null              # exit 0
"$knot" help topics | grep -q .         # bundled guides are in the jar
```

If any check fails, abort the cut.

### Step 10: Push

```bash
git push origin main --tags
```

### Step 11: Watch the release gate

The tag push starts the release gate. It builds the five release binaries,
smoke-tests each on its own platform plus the bbin install, and only then
creates the GitHub Release with the archives, `SHA256SUMS`, and the tag
annotation as its body. Do not create the Release by hand.

The run can take a few seconds to register after the push; if the id comes
back empty, wait and repeat.

```bash
gh run watch "$(gh run list --workflow release.yml --branch vX.Y.Z --limit 1 --json databaseId -q '.[0].databaseId')"
gh release view vX.Y.Z --json assets -q '.assets[].name'
```

Expect `knot-linux-amd64.tar.gz`, `knot-linux-aarch64.tar.gz`,
`knot-macos-amd64.tar.gz`, `knot-macos-aarch64.tar.gz`,
`knot-windows-amd64.zip` and `SHA256SUMS`. Without `gh`, follow the run
under the repo's Actions tab and check the assets on the Release page.

If the gate fails, no Release exists and the tag is a dead end. Delete it
locally and on origin (`git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z`),
fix on `main`, and cut again. If only the publish job failed, re-run it, or
dispatch `release.yml` with `tag: vX.Y.Z` and `dry_run: false`.

### Step 12: Cleanup

1. Verify every slice ticket is closed (any non-`closed` status counts as a
   leftover, including `in_progress`):

   ```bash
   knot list --tag vX.Y.Z --json \
     | jq -e '[.data[] | select(.status != "closed")] | length == 0'
   ```

   On non-empty: abort with the list of non-closed slices. (The open-children
   gate catches this in step 2 below anyway, but failing here gives a
   cleaner message.)

2. Close the release coordination ticket. The coord is conventionally
   `knot start`ed at release-cut time, so filter by "not closed" rather than
   `--status open` — `--status open` would miss the `in_progress` coord:

   ```bash
   coord=$(knot list --tag vX.Y.Z --tag release --json \
             | jq -r '[.data[] | select(.status != "closed")][0].id')
   knot close "$coord" --summary \
     "Cut vX.Y.Z. Release: <gh-release-url>."
   ```

   Terse summary — rich content already lives in CHANGELOG + tag message +
   GH Release body.

3. Delete the prose file:

   ```bash
   rm release-notes-vX.Y.Z.txt
   ```

## Constraints

- Do NOT push automatically. Step 10 needs explicit confirmation.
- Ask for confirmation before committing if changelog content is unclear.
- The `release-notes-v*.txt` pattern must already exist in `.gitignore`. If
  it doesn't, add it before proceeding.
