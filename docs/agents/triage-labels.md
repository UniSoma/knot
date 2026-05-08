# Triage Labels

The skills speak in terms of five canonical triage roles. knot has no first-class "label" concept — it has **tags** (free-form, plural per ticket) and **mode** (single-valued: `hitl` or `afk`). This file maps the canonical roles to the actual knot operations used in this repo.

| Canonical role     | knot operation                                      | Meaning                                  |
|--------------------|-----------------------------------------------------|------------------------------------------|
| `needs-triage`     | tag `triage` (`knot update <id> --tags triage,...`) | Maintainer needs to evaluate this issue  |
| `needs-info`       | tag `needs-info`                                    | Waiting on reporter for more information |
| `ready-for-agent`  | mode `afk` (`knot update <id> --mode afk`)          | Fully specified, ready for an AFK agent  |
| `ready-for-human`  | mode `hitl` (the default)                           | Requires human implementation            |
| `wontfix`          | `knot close <id> --summary "Won't do: ..."`         | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), translate via this table.

## Notes

- `mode` is mutually exclusive: a ticket is either `hitl` or `afk`, never both. So `ready-for-agent` and `ready-for-human` are flips of the same field, not additive labels.
- Tags are additive — `triage` and `needs-info` can coexist with domain tags. Use `knot update --tags <comma-list>` to set the full set; there is no "add one tag" command yet.
- `wontfix` is a *closed* state, not a label: closing with `--summary` starting with `Won't do:` is the convention (see `kno-01kqn3swv94c`).
