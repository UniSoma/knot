# Lifecycle gates

Two gates guard status transitions: the **acceptance gate** (terminal
transitions) and the **open-children gate** (start *and* close). A third,
opt-in check — the **conditional claim** (`--if-unassigned`) — guards who gets
to do the work. `SKILL.md` covers what fires the two gates and how to clear
them in the moment; this file is the on-demand reference for the full
skip-condition matrix, the start-vs-close `--summary` asymmetry, and the claim
predicate.

## Acceptance gate on terminal transitions

`knot close`, `knot status <id> <terminal>`, and `knot update <id> --status <terminal>` all enforce the v0.3 acceptance
gate: when the ticket is in `:active-status` (default `in_progress`) and any frontmatter `:acceptance` entry has `done:
false`, the transition is blocked (JSON `error.code = "acceptance_incomplete"`, exit 1).

The gate skips on:

- Empty / nil `:acceptance`.
- Intake → terminal transitions (no work was started).
- Terminal → terminal reclassifications (e.g. `closed → wontfix`).

Two ways to clear it:

1. Mark the AC done — `knot update <id> --ac <ordinal|"title"> --done`, repeatable. Composes with `--status` in one call: `knot update <id> --ac 4 --done --status closed` checks then closes.
2. `--force --summary "<reason>"`. Required pair: `--force` without a non-blank `--summary` exits `invalid_argument`. The summary is appended as a Notes entry and serves as the override record.

## Open-children gate on start and close transitions

The open-children gate fires on two transitions:

- **Close** (`active → terminal`): `knot close`, `knot status <id> <terminal>`, `knot update <id> --status <terminal>`.
- **Start** (`* → active`): `knot start`, `knot status <id> <active>`, `knot update <id> --status <active>`.

The gate fires when the ticket has at least one child (any ticket whose `:parent` is this id) whose status is
non-terminal (JSON `error.code = "open_children"`, exit 1 — same envelope shape for both transitions).

The gate skips on:

- Tickets with no children.
- Parents whose children are all in a terminal status.
- `active → active` no-op transitions and intake → terminal transitions (no meaningful start or close).
- Terminal → terminal reclassifications.

Override is `--force`, with asymmetric `--summary` semantics:

- **Close**: `--force --summary "<reason>"` is the required pair — `--force` without a non-blank `--summary` exits
  `invalid_argument`. The summary is appended as a Notes entry and serves as the override record. When both AC and
  open-children gates would fire on the same close, a single `--force` bypasses both and stderr emits one warning per
  gate.
- **Start**: `--force` alone is enough (no `--summary` required, and passing `--summary` to a non-terminal target is
  rejected up front). Start is provisional — you can `update --status` back to intake at zero cost — so the bypass
  leaves only the stderr enumeration as a trace, not a Notes entry.

## The conditional claim (`--if-unassigned`)

`knot start <id> --assignee <me> --if-unassigned` is the answer to "take this
ticket, but only if nobody else already did". The assignee is read off the
freshly-loaded ticket before the gates and before the write; a non-blank
assignee means nothing is written at all, the exit code is 1, and `--json`
reports `error.code = "already_assigned"` with `error.current_assignee`.
`knot update` accepts the same flag with the same semantics — and on a lost
claim it drops every other flag in that call, so `--if-unassigned --assignee me
--priority 0` never half-applies.

Judgment calls the flags cannot state:

- **Any** existing assignee loses the claim, your own handle included. The
  predicate answers "was this free?", not "is this mine?" — re-running a claim
  you already won reports `already_assigned`, which for a polling loop is the
  right answer (someone holds it; move on).
- Reach for it whenever more than one loop reads the same frontier. The
  companion read is `knot ready --assignee ""`, which lists the ready tickets
  nobody holds; without the claim predicate two agents can read that same list
  and both write.
- The read-modify-write window between the check and the save is small but
  real. This is a same-host courtesy protocol, not a lock — for genuinely
  hostile concurrency, partition the frontier instead (e.g. by `--parent`).
