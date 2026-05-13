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
|                        | `D`          | `knot-deps-transient`              |
|                        | `L`          | `knot-links-transient`             |
| `knot-show-mode`       | `RET`        | context action                     |
|                        | `+` / `a`    | `knot-show-add-ac`                 |
|                        | `-`          | `knot-show-remove-ac`              |
|                        | `s`          | `knot-start`                       |
|                        | `x`          | `knot-close`                       |
|                        | `M`          | `knot-update-from-show` (transient)|
|                        | `[` / `]`    | prev / next ticket                 |
|                        | `K`          | `knot-show-remove-at-point`        |
|                        | `D`          | `knot-deps-transient`              |
|                        | `L`          | `knot-links-transient`             |
|                        | `E`          | `knot-show-edit-via-emacsclient`   |
| `knot-deps-mode`       | `TAB` / `<backtab>` | navigation                  |
|                        | `f`          | `knot-deps-toggle-full`            |
|                        | `q`          | `knot-deps-quit`                   |

The `M` transient owns every field mutation — frontmatter suffixes
(`s` status, `p` priority, `m` mode, `t` type, `T` tags, `a` assignee,
`P` parent) alongside long-form suffixes (`e` description, `d` design,
`b` body, `n` note). The standalone show-mode bindings for `e d b n`
are dropped when `knot-evil-mode` is on; they collide with evil
operators and motions.

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
        :desc "Links"     "L" #'knot-links-transient)
  (map! :map knot-list-mode-map
        :localleader
        :desc "Filter"    "f" #'knot-list-filter
        :desc "Sort"      "o" #'knot-list-sort))
```

Doom users typically alias `,` to localleader, so `,` is not bound by
`knot-evil-mode`; the `M` binding replaces the non-evil `,` keymap
entry that opens the update transient.
