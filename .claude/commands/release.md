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
`CHANGELOG.md` follows Keep a Changelog format. New entries go at the top,
above the previous version (and below the `## Versioning` block).
</changelog>

<git-tags>
Tags use `vX.Y.Z` format (e.g. `v0.0.1`).
</git-tags>

## Procedure

### Step 1: Gather context

Run these commands to understand current state:

```bash
# Get current version
grep 'def version' src/knot/version.clj

# Get latest tag — if this command fails, this is the first release of the
# repo. In that case, run `git log --oneline` instead and reason about the
# full history.
git describe --tags --abbrev=0

# Get commits since last tag (only if a prior tag exists)
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

### Step 2: Determine new version

Based on commits since last tag, determine version bump:

- **patch** (X.Y.Z+1): Bug fixes, minor improvements
- **minor** (X.Y+1.0): New features, non-breaking changes
- **major** (X+1.0.0): Breaking changes

Ask user to confirm version if unclear.

### Step 3: Update version

Edit `src/knot/version.clj` to the new version.

### Step 4: Update CHANGELOG.md

Add a new section at the top (below the `## Versioning` block, above the
previous version):

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added/Changed/Fixed/Removed

- Description of change
```

Categorize commits appropriately:

- **Added**: New features
- **Changed**: Changes to existing functionality
- **Fixed**: Bug fixes
- **Removed**: Removed features

### Step 5: Commit release preparation

```bash
git add -A
git commit -m "Release vX.Y.Z"
```

The commit message uses the verb-leading convention used throughout this
repo — no Conventional Commits prefix.

### Step 6: Create tag

```bash
git tag vX.Y.Z
```

### Step 7: Report next steps

Tell user to push: `git push origin main --tags`.

## Constraints

- Do NOT push automatically.
- Ask for confirmation before committing if changelog content is unclear.
