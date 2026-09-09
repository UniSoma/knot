# Writes

Every write to a ticket picks between appending and overwriting, and the next one does not undo the last.

## Notes and revisions

`knot add-note` appends a timestamped entry — the tool for observations captured mid-task. `knot update` replaces:
`--description` the section, `--body` the entire body (destructive, git is the undo), fields and status in the same
call, non-interactively — the tool for scripts and autonomous runs. `knot edit` opens `$EDITOR` and needs a TTY. To add
to a ticket, reach for `add-note`.

## A render is not a body

Five sections in a `knot show` render — `## Acceptance Criteria`, `## Blockers`, `## Blocking`, `## Children`,
`## Linked` — are synthesized from the `acceptance`, `deps`, `parent`, and `links` fields and marked in the render by
an HTML comment naming the source. What you would have written under one goes through the owning field instead
(`--add-ac`, `knot dep`, `--parent`, `knot link`). `--body` refuses those five names; near-synonyms are not refused and
are the same mistake — a hand-written `## Blocked by`, `## Depends on`, or `## Parent document` is prose that stops
matching the graph the moment the graph moves.

## Replace vs delta

`--tags` and `--external-ref` replace the whole list, so re-sending a list to add one value drops anything you hadn't
read first. For a one-value change reach for the delta flags — `--add-tag` / `--remove-tag`, `--add-external-ref` /
`--remove-external-ref`, `--add-ac` / `--remove-ac` — which leave the rest untouched.

Address a criterion by the number `knot show` prints beside it rather than retyping it — real AC titles run to a
paragraph — and batch flips in one write: `knot update <id> --ac 2 --ac 5 --done`.
