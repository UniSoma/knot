---
id: kno-01kqsgmey9ew
title: Make prime AFK preamble selection config-driven instead of hardcoded afk
status: closed
type: bug
priority: 3
mode: afk
created: '2026-05-04T12:50:27.401087881Z'
updated: '2026-05-05T12:13:04.070200467Z'
closed: '2026-05-05T12:13:04.070200467Z'
parent: kno-01kqgqapwqvh
tags:
- v0.3
- audit
- cleanup
---

## Description

Audit finding from kno-01kqgqapwqvh.

Runtime site:
- src/knot/output.clj:664 selects prime-preamble-afk only when mode-norm equals \"afk\".

That means `knot prime --mode <custom-agent-mode>` cannot reach the AFK preamble if a project customizes :modes away from [\"afk\" \"hitl\"]. The fix may require deriving the AFK/HITL role from config explicitly, or deciding/documenting that mode names are semantically fixed and should not be customized.

Acceptance:
- No runtime branch in prime renderer depends on the literal string \"afk\".
- Behavior is either config-derived for custom mode names, or the config/schema/docs explicitly constrain mode semantics so the coupling is intentional.
- Tests pin the chosen contract.

## Notes

**2026-05-05T12:13:04.070200467Z**

Make prime AFK preamble selection config-driven via new :afk-mode config key. Add :afk-mode "afk" to knot.config/default-config and to known-keys; validate! rejects values not in :modes (nil opt-out allowed). knot.output/prime-text now dispatches on data-map :afk-mode (normalized both sides via the existing keyword/case/whitespace coercion) instead of the literal string "afk" — the cond branch is (= mode-norm afk-norm), and :or sources (:afk-mode (config/defaults)) so direct callers without the key still get back-compat. knot.cli/prime-cmd threads :afk-mode from the resolved ctx into the renderer data map (no-project branch sources (config/defaults)); info-data surfaces :afk_mode under :allowed_values; stub-config writes a documented :afk-mode line in the .knot.edn template. Output info-text renders 'Afk mode: <value>' (or '(none)' on opt-out) in the Allowed Values block. RED→GREEN slices: (1) renderer dispatches on :afk-mode (custom name reaches AFK preamble; literal "afk" loses load-bearing under custom config; nil opt-out; symmetric normalization) — pinned by prime-text-afk-mode-config-driven-test. (2) config validation (reject not-in-modes, allow nil, round-trip) — load-config-validation-test extensions plus defaults-test pin. (3) prime-cmd threads :afk-mode end-to-end (custom-modes path, opt-out, no-project back-compat) — prime-cmd-afk-mode-config-driven-test. (4) info surface — info-cmd-allowed-values-block-test for the JSON :afk_mode field; info-text-rendering-test for the rendered line + (none) opt-out. (5) init stub — init-cmd-test asserts the uncommented :afk-mode "afk" line plus an explanatory comment naming the agent/autonomous role. Existing prime-text-afk-mode-preamble-test stays green via the renderer's (config/defaults) fallback. Acceptance: no runtime branch in prime-text depends on literal "afk"; custom mode names reach the agent preamble via :afk-mode; nil disables it; tests pin all four. Tests: 292/2684/0 (was 290/2660/0; +2 deftests, +24 assertions). Lint baseline unchanged (4 errors / 5 warnings, all pre-existing). Files: src/knot/config.clj, src/knot/output.clj, src/knot/cli.clj, test/knot/config_test.clj, test/knot/output_test.clj, test/knot/cli_test.clj.
