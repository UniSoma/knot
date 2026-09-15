# Knot has no browser surface

`knot serve`, the read-only loopback browser panel, was removed on 2026-09-15. Knot's surfaces are the CLI, its `--json` envelope, and embedders built on that envelope, such as `knot.el`. Knot does not run a server. This supersedes [0005](0005-knot-serve-stack-layout.md) (stack layout), [0006](0006-knot-serve-is-read-only-in-v1.md) (read-only v1) and [0007](0007-knot-serve-shells-out-per-request.md) (shell-out per request) in full.

The panel came out of a prototype and never found a user. Nothing in the repo called it, `knot.el` shells out to the CLI directly, and neither the `--json` contract nor the check-code catalogue mentioned it. It still cost more than any other command. It was the one feature that contradicted the README's "no daemons, no servers" promise. It pulled in http-kit, a Java library, so it was the one command the jolt binary could not run. The runtime `resolve` that kept http-kit out of bb startup also blocked jolt's `--tree-shake`. Its integration test needed an installed `knot` on `PATH`, so CI installed bbin on all three platforms just for that test.

## Considered options

- **Keep it, unadvertised** — rejected. Unused code still carries its dependency, its CI setup and its jolt gap, and a command left in `knot --help` is a promise to maintain it.
- **Move it out to a separate tool** — rejected for now. There is no user to build it for. A browser view can be built on `knot --json` without changes to knot, which is exactly the contract 0007 chose.
- **Delete it and 0005–0007 without a record** — rejected. The layout, read-only and shell-out reasoning is still worth reading if anyone builds a browser view again. This ADR records why it is gone.

## Consequences

- `knot serve` and `knot help serve` report an unknown command. `src/knot/serve.clj`, its tests and `resources/knot/serve/` are deleted. `resources` stays on `:paths` for the bundled skill.
- CI no longer installs bbin or knot before `bb test`.
- The jolt binary has no command-level gap. `--tree-shake` is still blocked by `flatland.ordered.set`'s runtime lookup.
- [0011](0011-leverage-live-induced-deps-cone.md)'s "`knot serve` node-colored graph" is no longer a planned consumer of `leverage`. The always-on field stands on its other reasons.
- A future browser or other GUI surface needs a new ADR, and should consume `knot --json` from outside the CLI rather than live inside it.
