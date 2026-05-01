---
id: kno-01kqjavvr89d
title: Deflake prime-cmd-json-test truncation case (ms-clock id collision)
status: open
type: bug
priority: 0
mode: afk
created: '2026-05-01T17:54:57.416209665Z'
updated: '2026-05-01T17:54:57.416209665Z'
tags:
- v0.3
- test
- flake
---

## Description

The "truncation flag and remaining count" sub-test in prime-cmd-json-test (test/knot/cli_test.clj:2333) creates 5 tickets via dotimes+cli/create-cmd, but cli/create-cmd derives ids from a millisecond-resolution clock — two creates landing in the same ms collide and one file silently overwrites the other, so only 4 tickets exist. The test then expects ready_remaining:3 (5 - limit 2) and fails with ready_remaining:2 plus live_count:4 in the JSON.

The same test file already has a save-direct helper at line 2361 with a comment explicitly calling out this race: "cli/create-cmd derives the id from a millisecond clock, which makes short-prefix collision tests racy."

## Fix options

1. Switch the sub-test to use save-direct with deterministic ids (cheapest; matches the pattern other tests already use to avoid this).
2. Thread a deterministic :now or counter through cli/create-cmd so multi-create-in-same-ms tests are reliable everywhere (broader; would also fix any other latent ms-clock races).

Option 1 is the right scope for this ticket; option 2 is a bigger change that should be its own ticket if we decide it is worth doing.

## Acceptance Criteria

- [ ] prime-cmd-json-test passes deterministically across 100 consecutive bb test runs
- [ ] Fix uses the existing save-direct helper or an equivalent deterministic-id path; does not silently sleep
- [ ] No new flake introduced in adjacent tests
