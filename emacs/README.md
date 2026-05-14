# knot.el

Emacs UI for the [knot](../README.md) CLI: tabulated-list views, a
markdown-view-mode show buffer, transient menus for every mutation,
capture buffers for long-form fields, and a dedicated dep-tree view.

## Install

The package is a single file (`knot.el`) and depends on
[`markdown-mode`](https://github.com/jrblevin/markdown-mode). With
`straight.el`:

```elisp
(use-package knot
  :straight (knot :type git :host github :repo "UniSoma/knot"
                  :files ("emacs/knot.el"))
  :commands (knot knot-list knot-show))
```

`M-x knot` opens the dispatch transient. Project detection mirrors
`magit-toplevel` — the buffer's `default-directory` is anchored to
the project root resolved by `knot info --json`.

## Global navigation

`knot-find-id-at-point` opens the show buffer for the ticket id
under point from any buffer — prose notes, source comments, magit
log, dired, anywhere the project's id shape (`<prefix>-01<base32>`)
appears as plain text. Point may be on or adjacent to the id, like
`find-file-at-point`. Partial ids resolve via the CLI's partial-id
contract. `q` in the resulting show buffer returns to the call
site, including when the show buffer already existed from an
earlier session. With no id at point the command signals
`user-error "No knot id at point"` rather than prompting — use
`M-x knot-show` (or `?` → `s`) for the prompt path.

Suggested global bind:

```elisp
(keymap-global-set "C-c k v" #'knot-find-id-at-point)
```

## Smooth scrolling

`knot-list-mode` and `knot-deps-mode` enable `pixel-scroll-precision-mode`
on entry so trackpad scrolling feels native. The mode is a global minor
mode and is CPU-intensive on some setups; opt out by setting
`knot-pixel-scroll` to `nil` before either buffer is created. Setting
the variable to `nil` after the mode is already active does not disable
it — knot only declines to turn it on.

```elisp
(setq knot-pixel-scroll nil)
```

## Evil / Doom users

`knot-evil-mode` is an opt-in global minor mode that rewires knot.el's
read-only buffers for evil normal state. `evil` is a soft dependency;
the package loads without it, and toggling the mode on raises
`user-error` when evil is missing.

Activation is destructive in the evil-collection sense — the setup
function strips the colliding stock-Emacs bindings from each
`knot-*-mode-map` and adds the new bindings via `evil-define-key*`
into each mode's auxiliary keymap for normal state. The mode is
idempotent; toggling it off does not restore the original bindings
(reload `knot.el` to revert).

### Binding table

All bindings live in evil **normal state**. Visual state is unbound
(no bulk-action workflow). `knot-capture-mode` opens in **insert**
state; `C-c C-c` commits and `C-c C-k` discards regardless of state.

| Scope                  | Key          | Command                            |
|------------------------|--------------|------------------------------------|
| Global (read-only)     | `?`          | `knot` dispatch                    |
|                        | `gr`         | `knot-refresh`                     |
|                        | `q`          | context quit                       |
| `knot-list-mode`       | `RET`        | `knot-list-show-at-point`          |
|                        | `f`          | `knot-list-filter`                 |
|                        | `o`          | `knot-list-sort`                   |
|                        | `s`          | `knot-start`                       |
|                        | `x`          | `knot-close`                       |
|                        | `M`          | `knot-update-from-show` (transient)|
|                        | `m`          | `knot-list-mark`                   |
|                        | `u`          | `knot-list-unmark`                 |
|                        | `U`          | `knot-list-unmark-all`             |
|                        | `D`          | `knot-deps-transient`              |
|                        | `L`          | `knot-links-transient`             |
|                        | `T`          | `knot-tags-transient`              |
| `knot-show-mode`       | `RET`        | context action                     |
|                        | `+`          | `knot-show-add-at-point`           |
|                        | `-` / `K`    | `knot-show-remove-at-point`        |
|                        | `s`          | `knot-start`                       |
|                        | `x`          | `knot-close`                       |
|                        | `M`          | `knot-update-from-show` (transient)|
|                        | `[` / `]`    | prev / next ticket                 |
|                        | `D`          | `knot-deps-transient`              |
|                        | `L`          | `knot-links-transient`             |
|                        | `T`          | `knot-tags-transient`              |
|                        | `E`          | `knot-show-edit-via-emacsclient`   |
| `knot-deps-mode`       | `TAB` / `<backtab>` | navigation                  |
|                        | `f`          | `knot-deps-toggle-full`            |
|                        | `q`          | `knot-deps-quit`                   |

`m` / `u` / `U` add dired-style marks to `knot-list-mode` rows. `m`
marks the row at point and advances one line; `u` unmarks and
advances; `U` clears the entire set. With an active region, `m` and
`u` operate on every overlapping row and deactivate the mark.
Marked rows render a `*` in the padding column and the
`knot-marked` face on the row body; the header-line gains a
`[N marked]` chunk while the set is non-empty. The set is buffer-
local and survives refresh (`g` / `gr`), filter changes, and sort
changes — point is preserved across refresh on the same row id,
and ids that fall out of view (e.g. after a filter change or an
external mutation) silently drop. Switching view with `l` / `r` /
`b` / `c` clears the set, since the row set is conceptually
different across views.

When `knot-update-from-show` (`,` / `M`) is invoked in
`knot-list-mode` with marks non-empty, each frontmatter suffix
fans out across the marked set: the prompt prefixes a
`(N marked)` chunk and offers no default value, then one
`knot update --<flag> <value>` subprocess runs per id in display
order. Per-id `user-error` failures are caught and the loop
continues; a single `knot--after-mutation` refresh fires once at
the end. The minibuffer reports the outcome as `M: K ok` or
`M: K ok, F failed: <id> (reason), …`. Marks persist across the
run, and rows that fall out of the current view (e.g. status
flipped to closed under the `ready` view) auto-prune at refresh
time. With marks empty the existing single-id behavior applies
(operate on the row at point, current-value default still
prefills). `s` (start) and `x` (close) remain single-id; bulk
long-form mutation (`e d b n`) is intentionally excluded.

`+` in `knot-show-mode` is field- and section-aware. On the
`tags:` frontmatter line (the `knot-field` value span) it
dispatches to `knot-update-add-tags`. Otherwise it reads the
`knot-section` text property at point and dispatches:
`## Acceptance Criteria` → `knot-show-add-ac`, `## Blockers` →
`knot-deps-add`, `## Linked` → `knot-links-add`, `## Blocking` →
`knot-show-add-rdep` (adds a ticket that depends on the current
one). When point is outside any of those four sections and not
on the `tags:` line, `+` pops `knot-show-add-transient` (`a`
acceptance, `d` dep, `l` link, `t` tag). The four `+`-aware
sections are always rendered, with an italic placeholder when
empty.

`-` and `K` both call `knot-show-remove-at-point`, the symmetric
remover — dispatching on the row's text property: the `tags:`
line → `knot-update-remove-tags`, a `## Blockers` row → `knot
undep`, a `## Blocking` row → reverse `knot undep`, a `## Linked`
row → `knot unlink`, an acceptance row → `--remove-ac`. `-`
shadows evil's `evil-previous-line-first-non-blank` motion in
knot-show buffers; use `k` for up-line.

RET on the `tags:` line still invokes the full `knot-update-set-tags`
replace via `knot-show--field->command`; use `+` / `-` for deltas
and `RET` for a full replace, or pop `T` for the full transient.

The `M` transient owns every field mutation and opens from both
`knot-show-mode` and `knot-list-mode` (operating on the row at point
in the latter). Frontmatter suffixes (`s` status, `p` priority, `m`
mode, `t` type, `a` assignee, `P` parent) are always offered;
long-form suffixes (`e` description, `d` design, `b` body, `n` note)
are shown only in `knot-show-mode` — drill into a row via `RET` first
to edit long-form sections. The standalone show-mode bindings for
`e d b n` are dropped when `knot-evil-mode` is on; they collide with
evil operators and motions.

Tags live behind their own top-level `T` transient
(`knot-tags-transient`), parallel to `D` deps / `L` links: `a`
`knot-update-add-tags` and `r` `knot-update-remove-tags` apply
deltas via `--add-tag` / `--remove-tag` (idempotent, repeatable,
with `completing-read-multiple` over the project-wide tag union
for add and over the current-or-marked tag union for remove);
`T` invokes the existing `knot-update-set-tags` for a full
`--tags` replace. All three honor `knot-list--marks` for fan-out;
empty input is a silent no-op.

The `f` transient (`knot-list-filter`) scopes the active view via the
same flags the CLI accepts: `m` mode, `t` type, `s` status, `T` tag,
`a` assignee, `p` priority (0..4), `l` limit, `A` acceptance-complete,
`C` clear all. Empty input on any prompt clears the filter for that
dimension.

### Doom Emacs

Paste-ready snippet for `~/.doom.d/packages.el` + `config.el`:

```elisp
;; packages.el
(package! knot
  :recipe (:host github :repo "UniSoma/knot" :files ("emacs/knot.el")))
```

```elisp
;; config.el
(use-package! knot
  :commands (knot knot-list knot-show)
  :config
  (knot-evil-mode +1)
  (map! :map (knot-list-mode-map knot-show-mode-map
              knot-info-mode-map knot-deps-mode-map)
        :localleader
        :desc "Dispatch"  "?" #'knot
        :desc "Refresh"   "r" #'knot-refresh
        :desc "Start"     "s" #'knot-start
        :desc "Close"     "x" #'knot-close
        :desc "Modify"    "M" #'knot-update-from-show
        :desc "Deps"      "D" #'knot-deps-transient
        :desc "Links"     "L" #'knot-links-transient
        :desc "Tags"      "T" #'knot-tags-transient)
  (map! :map knot-list-mode-map
        :localleader
        :desc "Filter"    "f" #'knot-list-filter
        :desc "Sort"      "o" #'knot-list-sort))
```

Doom users typically alias `,` to localleader, so `,` is not bound by
`knot-evil-mode`; the `M` binding replaces the non-evil `,` keymap
entry that opens the update transient in both `knot-show-mode` and
`knot-list-mode`.
