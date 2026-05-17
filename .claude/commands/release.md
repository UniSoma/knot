---
description: Cut a new release with version bump, changelog, commit, and tag
allowed-tools:
  - Read
  - Edit
  - Bash
  - Glob
  - Grep
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

For each closed ticket and each commit, verify a corresponding bullet exists
under `[Unreleased]`. Backfill any missing entries **while the section is
still `[Unreleased]`** — once it's renamed in Step 5, the section is dated
and effectively sealed.

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
body (Step 11).

Canonical structure (modeled on v0.3.0's shipped tag message):

~~~
Release vX.Y.Z — <one-line theme>

<1-3 sentence lead: breaking? headline change?>

## Highlights

- **<phrase>.** <description>

## Breaking changes        # omit entire section for non-breaking releases

1. **<title>.** <description>

   Migration: <command or one-liner>

## Upgrade path

```sh
<bbin reinstall + per-release verification>
```

## Known follow-ups        # optional — omit when empty

- <ticket id> — <one-liner>
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

Install from the working copy and verify the binary starts and reports the
new version. `info`/`check` are deliberately out of scope here — they'd
either need a knot project or risk false-positive failures against this
repo's own tickets. Full end-to-end coverage lives in the release-tag smoke
CI workflow (separate from this slash command).

```bash
bbin install . --as knot-rc
knot-rc --version | grep -q "X.Y.Z"   # grep-asserted version string
knot-rc --help                        # exit 0
bbin uninstall knot-rc                # unconditional, even on failure
```

If `--version` doesn't match or `--help` fails, abort the cut and uninstall
before exiting.

### Step 10: Push

```bash
git push origin main --tags
```

### Step 11: Create GitHub Release

Prefer the `gh` CLI when available:

```bash
gh release create vX.Y.Z \
  -F release-notes-vX.Y.Z.txt \
  --title "vX.Y.Z" \
  --verify-tag
```

`--verify-tag` refuses to create a release for a tag that doesn't exist
remotely — catches "forgot to push tags" mistakes.

Web-UI fallback when `gh` is not installed: open
`github.com/<owner>/<repo>/releases/new`, paste `release-notes-vX.Y.Z.txt`
verbatim into the body, set tag-target to `vX.Y.Z`, publish.

Mark as "latest" (the default). No `--prerelease` flag.

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
