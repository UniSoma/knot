;;; knot.el --- Emacs UI for the knot CLI -*- lexical-binding: t; -*-

;; Copyright (c) 2026 UniSoma

;; Author: UniSoma
;; URL: https://github.com/unisoma/knot
;; Package-Requires: ((emacs "28.1") (markdown-mode "2.5"))
;; Version: 0.1.0
;; Keywords: tools, vc

;; This file is distributed under the MIT License.  See the LICENSE
;; file at the root of the repository for the full text.

;;; Commentary:
;;
;; knot.el fronts the knot CLI with a magit-style UI.  Tabulated-list
;; buffers render listings, markdown-view show buffers render single
;; tickets, transient menus expose every mutation, and capture buffers
;; collect long-form fields.  All CLI traffic flows through one
;; boundary function, `knot-cli-call', which speaks exclusively to the
;; CLI's --json envelope.
;;
;; This file currently lands slices 1-5 and slice 7 of the v0.1 plan:
;; the CLI boundary, the project oracle (`knot-info-current'), the
;; dispatch transient (`M-x knot'), a single project-scoped list
;; buffer that flips in place between list / ready / blocked / closed
;; views (`l' / `r' / `b' / `c'), with a filter transient on `f'
;; covering --mode / --type / --status / --tag / --assignee / --limit
;; / --acceptance-complete and `g' as the manual refresh, a
;; markdown-view-mode show buffer with buttonized ticket ids, AC-line
;; RET flipping done/undone, +/a / -/k for AC add/remove, ]/[ for
;; next/previous in the originating list buffer, a multi-level
;; back-button on `q' that walks the entry chain across drill-ins,
;; an update transient on `,' that commits atomic frontmatter
;; mutations (status / priority / mode / type / tags / assignee /
;; parent) as one `knot update --flag value' subprocess each,
;; capture buffers on `e' / `d' / `b' (edit description / design /
;; body) and `n' (add note) that commit on `C-c C-c' or discard on
;; `C-c C-k', a capital-`E' escape hatch that shells out to
;; `knot edit <id>' with `EDITOR=emacsclient' for arbitrary
;; structural edits, a `D' deps transient and `L' links transient
;; (in show and list) with add (live tickets via completing-read) /
;; remove (current deps or links, archive titles merged in) /
;; tree-open suffixes, `k' extended to undep / unlink the
;; relationship at point in show after yes/no confirmation, and a
;; dedicated `*knot-deps: <project> · <id>*' buffer that renders
;; `knot dep tree --json' as an indented outline with ✓/○ status
;; glyphs, node buttons that drill into show, `f' to toggle
;; collapsed vs --full, and `q' to walk back to the buffer that
;; opened it.  Create / quick-create (slice 6) and cross-buffer
;; refresh + CLI-version compat warning (slice 8) arrive in
;; subsequent slices.
;;
;; The knot binary is located via `executable-find' on
;; `knot-executable' (default \"knot\") and run synchronously per
;; call.  Project detection mirrors `magit-toplevel': `knot info
;; --json' is run from `default-directory' on first use and cached
;; per directory, with each knot.el buffer capturing the resolved
;; project root as its buffer-local `default-directory'.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'tabulated-list)
(require 'transient)
(require 'button)
;; markdown-mode is declared in Package-Requires; slice 3's show
;; buffer derives from `markdown-view-mode'.
(require 'markdown-mode)


;;;; Customization

(defgroup knot nil
  "Emacs UI for the knot CLI."
  :group 'tools
  :prefix "knot-"
  :link '(url-link :tag "Repository" "https://github.com/unisoma/knot"))

(defcustom knot-emacsclient-executable "emacsclient"
  "Executable used as `EDITOR' for the capital-E escape hatch.
The capital-`E' show-buffer command shells out to
`knot edit <id>' with this binary set as the child process's
`EDITOR' (and `VISUAL') so that the ticket file opens in the
running Emacs server."
  :type 'string
  :group 'knot)

(defcustom knot-executable "knot"
  "Name of, or path to, the knot binary.
Resolved via `executable-find' at call time."
  :type 'string
  :group 'knot)

(defcustom knot-minimum-cli-version "0.3.0"
  "Lowest knot CLI version knot.el is known to work with.
Slice 8 will warn when the running CLI is older than this value."
  :type 'string
  :group 'knot)


;;;; Faces (knot-format module)

(defface knot-status-open
  '((t :inherit default))
  "Face for ticket status `open'."
  :group 'knot)

(defface knot-status-in-progress
  '((t :inherit warning))
  "Face for ticket status `in_progress'."
  :group 'knot)

(defface knot-status-closed
  '((t :inherit shadow))
  "Face for ticket status `closed'."
  :group 'knot)

(defface knot-priority-0
  '((t :inherit error :weight bold))
  "Face for priority 0 (highest)."
  :group 'knot)

(defface knot-priority-1
  '((t :inherit warning :weight bold))
  "Face for priority 1."
  :group 'knot)

(defface knot-priority-2
  '((t :inherit default))
  "Face for priority 2."
  :group 'knot)

(defface knot-priority-3
  '((t :inherit shadow))
  "Face for priority 3."
  :group 'knot)

(defface knot-priority-4
  '((t :inherit shadow))
  "Face for priority 4."
  :group 'knot)

(defface knot-mode-afk
  '((t :inherit success))
  "Face for mode `afk'."
  :group 'knot)

(defface knot-mode-hitl
  '((t :inherit default))
  "Face for mode `hitl'."
  :group 'knot)

(defface knot-type-bug
  '((t :inherit error))
  "Face for type `bug'."
  :group 'knot)

(defface knot-type-feature
  '((t :inherit font-lock-keyword-face))
  "Face for type `feature'."
  :group 'knot)

(defface knot-type-task
  '((t :inherit default))
  "Face for type `task'."
  :group 'knot)

(defface knot-type-epic
  '((t :inherit font-lock-type-face))
  "Face for type `epic'."
  :group 'knot)

(defface knot-type-chore
  '((t :inherit shadow))
  "Face for type `chore'."
  :group 'knot)

(defface knot-id
  '((t :inherit link))
  "Face for ticket ids."
  :group 'knot)

(defface knot-deps-seen-before
  '((t :inherit shadow))
  "Face for the ↑ marker on seen-before nodes in the deps tree."
  :group 'knot)

(defface knot-deps-missing
  '((t :inherit font-lock-warning-face))
  "Face for nodes in the deps tree that reference a missing ticket."
  :group 'knot)

(defun knot-format-propertize (string face)
  "Return STRING propertized with FACE when FACE is non-nil."
  (if (and string face)
      (propertize string 'face face)
    (or string "")))

(defun knot-format-status (status)
  "Propertize STATUS string with the matching face."
  (let ((face (pcase status
                ("open" 'knot-status-open)
                ("in_progress" 'knot-status-in-progress)
                ("closed" 'knot-status-closed)
                (_ nil))))
    (knot-format-propertize (or status "") face)))

(defun knot-format-priority (priority)
  "Propertize integer PRIORITY with the matching face."
  (let* ((string (if (numberp priority) (number-to-string priority) ""))
         (face (pcase priority
                 (0 'knot-priority-0)
                 (1 'knot-priority-1)
                 (2 'knot-priority-2)
                 (3 'knot-priority-3)
                 (4 'knot-priority-4)
                 (_ nil))))
    (knot-format-propertize string face)))

(defun knot-format-mode (mode)
  "Propertize MODE string with the matching face."
  (let ((face (pcase mode
                ("afk" 'knot-mode-afk)
                ("hitl" 'knot-mode-hitl)
                (_ nil))))
    (knot-format-propertize (or mode "") face)))

(defun knot-format-type (type)
  "Propertize TYPE string with the matching face."
  (let ((face (pcase type
                ("bug" 'knot-type-bug)
                ("feature" 'knot-type-feature)
                ("task" 'knot-type-task)
                ("epic" 'knot-type-epic)
                ("chore" 'knot-type-chore)
                (_ nil))))
    (knot-format-propertize (or type "") face)))


;;;; CLI boundary (knot-cli module)

(defun knot-cli--program ()
  "Return the resolved knot executable path."
  (or (executable-find knot-executable)
      knot-executable))

(defun knot-cli-call (args &optional stdin)
  "Run the knot binary with ARGS, return the envelope's `data' field.
ARGS is a list of strings passed as argv; --json is appended.

When STDIN is non-nil it is a string piped to the subprocess's
standard input via `call-process-region'.

Signals `user-error' when the envelope reports ok:false, when the
subprocess emits no parseable output, or when JSON parsing fails."
  (let* ((program (knot-cli--program))
         (final-args (append args (list "--json"))))
    (with-temp-buffer
      (let ((exit (if stdin
                      (apply #'call-process-region
                             stdin nil program nil t nil final-args)
                    (apply #'call-process
                           program nil t nil final-args))))
        (knot-cli--parse exit (buffer-string))))))

(defun knot-cli--parse (exit output)
  "Parse the knot --json envelope in OUTPUT, given subprocess EXIT code."
  (when (or (null output) (string-empty-p output))
    (user-error "knot: no output from subprocess (exit %s)" exit))
  (let* ((envelope (condition-case err
                       (json-parse-string output
                                          :object-type 'alist
                                          :array-type 'list
                                          :null-object nil
                                          :false-object nil)
                     (error
                      (user-error "knot: failed to parse JSON (exit %s): %s"
                                  exit (error-message-string err)))))
         (ok (alist-get 'ok envelope)))
    (if ok
        (alist-get 'data envelope)
      (let* ((error-obj (alist-get 'error envelope))
             (message (or (alist-get 'message error-obj)
                          (format "knot exited with code %s" exit))))
        (user-error "knot: %s" message)))))


;;;; Project oracle (knot-info module)

(defvar knot-info--cache (make-hash-table :test 'equal)
  "Cache mapping directory paths to the `data' field of `knot info --json'.")

(defvar knot-info--version-warned (make-hash-table :test 'equal)
  "Set of project roots for which the CLI version warning has already fired.
Keyed by `file-truename'-ed project root; values are non-nil sentinels.")

(defun knot-info--check-cli-version (data key)
  "Warn once per project when DATA's CLI version is below the declared minimum.
KEY is the truename of the project root the warning should be
remembered against.  Compares `data.project.knot_version' to
`knot-minimum-cli-version' via `version<' and calls `lwarn'
without refusing to load when the running CLI is older.  Silent
when the version is missing or unparseable."
  (let* ((project (alist-get 'project data))
         (running (alist-get 'knot_version project))
         (minimum knot-minimum-cli-version))
    (when (and (stringp running)
               (not (string-empty-p running))
               (stringp minimum)
               (not (string-empty-p minimum))
               (not (gethash key knot-info--version-warned)))
      (condition-case nil
          (when (version< running minimum)
            (lwarn 'knot :warning
                   "knot CLI %s is older than knot.el's declared minimum %s — \
some commands may behave unexpectedly.  Upgrade the CLI to silence this warning."
                   running minimum))
        (error nil))
      (puthash key t knot-info--version-warned))))

(defun knot-info-current (&optional directory)
  "Return the cached info envelope for DIRECTORY (default `default-directory').
On a cache miss, runs `knot info --json' from DIRECTORY and caches
the parsed `data' field.  Fires `knot-info--check-cli-version' on
each fresh fetch so the version-compat warning surfaces in
`*Warnings*' the first time a project is touched."
  (let* ((key (file-truename (or directory default-directory)))
         (hit (gethash key knot-info--cache)))
    (or hit
        (let* ((default-directory key)
               (data (knot-cli-call '("info"))))
          (puthash key data knot-info--cache)
          (knot-info--check-cli-version data key)
          data))))

(defun knot-info-allowed-values (field)
  "Return FIELD's entry from `allowed_values' in the current info envelope.
FIELD is a symbol such as `statuses', `types', `modes', or
`priority_range'."
  (let* ((data (knot-info-current))
         (allowed (alist-get 'allowed_values data)))
    (alist-get field allowed)))

(defun knot-info-defaults (field)
  "Return FIELD's entry from `defaults' in the current info envelope.
FIELD is a symbol such as `default_type', `default_priority', or
`default_mode'."
  (let* ((data (knot-info-current))
         (defaults (alist-get 'defaults data)))
    (alist-get field defaults)))

(defun knot-info-invalidate (&optional directory)
  "Clear the info cache for DIRECTORY, or all entries when DIRECTORY is nil.
Does not clear `knot-info--version-warned'; the version warning
is intentionally one-shot per Emacs session even after manual
`g' refreshes invalidate the data cache."
  (if directory
      (remhash (file-truename directory) knot-info--cache)
    (clrhash knot-info--cache)))

(defun knot-info--project-name (&optional info)
  "Return the project name from INFO, falling back to prefix or \"knot\"."
  (let* ((info (or info (knot-info-current)))
         (project (alist-get 'project info)))
    (or (alist-get 'name project)
        (alist-get 'prefix project)
        "knot")))

(defun knot-info--project-root (&optional info)
  "Return the project root path from INFO."
  (let* ((info (or info (knot-info-current)))
         (paths (alist-get 'paths info)))
    (alist-get 'project_root paths)))


;;;; Id display + buttonize (knot-id module)

(defun knot-id-format (id &optional title)
  "Return a display string for ID, optionally followed by TITLE.
The id substring is propertized with `knot-id'."
  (let ((label (propertize (or id "") 'face 'knot-id)))
    (if (and title (not (string-empty-p title)))
        (format "%s  %s" label title)
      label)))

(defun knot-id--regexp ()
  "Return a regexp matching ticket ids for the current project.
The pattern is derived from `knot info --json's `project.prefix';
falls back to a generic lowercase-prefix shape when info is
unavailable."
  (let ((prefix (or (alist-get 'prefix
                               (alist-get 'project (knot-info-current)))
                    "[a-z][a-z0-9]*")))
    (concat "\\<" (regexp-quote prefix) "-01[0-9a-z]+\\>")))

(defun knot-id--button-action (button)
  "Open the show buffer for BUTTON's `knot-id' property.
When invoked from a `knot-show-mode' buffer, the calling buffer
is recorded as the destination's back-buffer so `q' walks the
drill-in chain."
  (let ((id (button-get button 'knot-id))
        (back (and (derived-mode-p 'knot-show-mode)
                   (current-buffer))))
    (when (and id (not (string-empty-p id)))
      (knot-show--open id nil nil back))))

(define-button-type 'knot-id
  'help-echo "RET: open ticket"
  'follow-link t
  'face 'knot-id
  'action #'knot-id--button-action)

(defun knot-id-buttonize-region (beg end)
  "Buttonize every ticket id in [BEG, END].
Each match becomes a `knot-id' text-button carrying the id
string in its `knot-id' property."
  (save-excursion
    (let ((re (knot-id--regexp)))
      (goto-char beg)
      (while (re-search-forward re end t)
        (let ((mb (match-beginning 0))
              (me (match-end 0))
              (id (match-string-no-properties 0)))
          (make-text-button mb me
                            :type 'knot-id
                            'knot-id id))))))


;;;; Dispatch transient (knot-dispatch module)

;;;###autoload (autoload 'knot "knot" "Open the knot dispatch transient." t)
(transient-define-prefix knot ()
  "Dispatch transient for knot."
  ["Views"
   ("l" "list"      knot-list)
   ("r" "ready"     knot-ready)
   ("b" "blocked"   knot-blocked)]
  ["Create"
   ("c" "create"    knot-create)
   ("C" "quick"     knot-create-quick)]
  ["Other"
   ("o" "closed"    knot-closed)
   ("i" "info"      knot-info-show)
   ("g" "refresh"   knot-refresh)])

;;;###autoload
(defalias 'knot-status #'knot
  "Dispatch entry alias.  Bind `C-c k' (or similar) to `knot' or `knot-status'.")


;;;; View entry points (l/r/b/o suffixes on the dispatch transient)

;;;###autoload
(defun knot-ready ()
  "Open the project's list buffer at the `ready' view."
  (interactive)
  (knot-list--open 'ready))

;;;###autoload
(defun knot-blocked ()
  "Open the project's list buffer at the `blocked' view."
  (interactive)
  (knot-list--open 'blocked))

;;;###autoload
(defun knot-closed ()
  "Open the project's list buffer at the `closed' view."
  (interactive)
  (knot-list--open 'closed))

;;;; Info viewer

(defun knot-info-show ()
  "Pop a buffer summarizing the cached info envelope for this project."
  (interactive)
  (let* ((info (knot-info-current))
         (project (knot-info--project-name info))
         (project-root (knot-info--project-root info))
         (buf-name (format "*knot-info: %s*" project))
         (inhibit-read-only t))
    (with-current-buffer (get-buffer-create buf-name)
      (knot-info-mode)
      (setq-local default-directory
                  (file-name-as-directory (or project-root default-directory)))
      (erase-buffer)
      (knot-info--render info)
      (goto-char (point-min)))
    (pop-to-buffer buf-name)))

(defun knot-info--render (info)
  "Render INFO into the current buffer as a human-readable summary."
  (let* ((project (alist-get 'project info))
         (paths   (alist-get 'paths info))
         (defaults (alist-get 'defaults info))
         (allowed  (alist-get 'allowed_values info))
         (counts   (alist-get 'counts info)))
    (insert (format "Project:        %s\n"
                    (or (alist-get 'name project) "(unnamed)")))
    (insert (format "Prefix:         %s\n"
                    (or (alist-get 'prefix project) "(unset)")))
    (insert (format "CLI version:    %s\n"
                    (or (alist-get 'knot_version project) "(unknown)")))
    (insert (format "Project root:   %s\n"
                    (or (alist-get 'project_root paths) "(unknown)")))
    (insert (format "Config path:    %s\n"
                    (or (alist-get 'config_path paths) "(unknown)")))
    (insert (format "Tickets dir:    %s\n"
                    (or (alist-get 'tickets_path paths) "(unknown)")))
    (insert "\nDefaults:\n")
    (insert (format "  type:        %s\n" (alist-get 'default_type defaults)))
    (insert (format "  priority:    %s\n" (alist-get 'default_priority defaults)))
    (insert (format "  mode:        %s\n" (alist-get 'default_mode defaults)))
    (insert (format "  assignee:    %s\n"
                    (or (alist-get 'effective_create_assignee defaults)
                        (alist-get 'default_assignee defaults)
                        "(none)")))
    (insert "\nAllowed values:\n")
    (insert (format "  statuses:    %s\n"
                    (string-join (alist-get 'statuses allowed) ", ")))
    (insert (format "  types:       %s\n"
                    (string-join (alist-get 'types allowed) ", ")))
    (insert (format "  modes:       %s\n"
                    (string-join (alist-get 'modes allowed) ", ")))
    (let ((range (alist-get 'priority_range allowed)))
      (insert (format "  priority:    %s..%s\n"
                      (alist-get 'min range)
                      (alist-get 'max range))))
    (insert "\nCounts:\n")
    (insert (format "  live:        %s\n" (alist-get 'live_count counts)))
    (insert (format "  archive:     %s\n" (alist-get 'archive_count counts)))
    (insert (format "  total:       %s\n" (alist-get 'total_count counts)))))

(defvar knot-info-mode-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map special-mode-map)
    (define-key map (kbd "?") #'knot)
    (define-key map (kbd "g") #'knot-refresh)
    map)
  "Keymap for `knot-info-mode'.")

(define-derived-mode knot-info-mode special-mode "Knot-Info"
  "Major mode for the knot info buffer.")


;;;; List view (knot-list module — list/ready/blocked/closed with switching)

(defconst knot-list--views '(list ready blocked closed)
  "Symbols naming every view the list buffer can render.")

(defvar-local knot-list--view 'list
  "Symbol naming the active view in this `knot-list-mode' buffer.
One of `knot-list--views'.")

(defvar-local knot-list--filters nil
  "Active filter alist for this `knot-list-mode' buffer.
Each entry is (KEY . VALUE) where KEY is a symbol from
`knot-list--filter-keys' and VALUE is a string (or t / nil for
the boolean `acceptance-complete' filter).  Filters with nil or
empty-string values are treated as unset.")

(defconst knot-list--filter-keys
  '(mode type status tag assignee limit acceptance-complete)
  "Filter keys accepted by the list views, in display order.")

(defconst knot-list--filter-cli-flags
  '((mode                . "--mode")
    (type                . "--type")
    (status              . "--status")
    (tag                 . "--tag")
    (assignee            . "--assignee")
    (limit               . "--limit")
    (acceptance-complete . "--acceptance-complete"))
  "Map from filter key to the CLI flag string.")

(defun knot-list--view-accepts-p (_view _key)
  "Return non-nil when VIEW accepts filter KEY.
For slice 2 every view accepts every filter; the hook stays to
let later slices degrade gracefully when CLI flag surfaces
diverge (see kno-01kreh3g266x AC #4)."
  t)

(defun knot-list--filter-string-value (value)
  "Return VALUE as a non-empty filter string, or nil when unset."
  (cond
   ((null value) nil)
   ((stringp value) (and (not (string-empty-p value)) value))
   ((numberp value) (number-to-string value))
   ((eq value t) "true")
   (t nil)))

(defun knot-list--effective-filters (view filters)
  "Return FILTERS pruned to entries accepted by VIEW with usable values."
  (cl-loop for (key . value) in filters
           for canonical = (knot-list--filter-string-value value)
           when (and canonical (knot-list--view-accepts-p view key))
           collect (cons key canonical)))

(defun knot-list--build-args (view filters)
  "Return CLI argv for VIEW with FILTERS applied.
Drops filters whose values are nil/empty or that VIEW does not
accept."
  (let ((args (list (symbol-name view))))
    (dolist (entry (knot-list--effective-filters view filters))
      (let* ((key (car entry))
             (value (cdr entry))
             (flag (cdr (assq key knot-list--filter-cli-flags))))
        (setq args (append args (list flag value)))))
    args))

(defun knot-list--header-line (view filters)
  "Render the header-line string for VIEW and active FILTERS."
  (let* ((view-tag (propertize (format "[%s]" view)
                               'face 'mode-line-emphasis))
         (effective (knot-list--effective-filters view filters))
         (flag-strs (mapcar (lambda (entry)
                              (format "%s=%s"
                                      (substring
                                       (cdr (assq (car entry)
                                                  knot-list--filter-cli-flags))
                                       2)
                                      (cdr entry)))
                            effective)))
    (concat view-tag
            " "
            (if flag-strs
                (string-join flag-strs " ")
              (propertize "(no filters)" 'face 'shadow)))))

(defvar knot-list-mode-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map tabulated-list-mode-map)
    (define-key map (kbd "?") #'knot)
    (define-key map (kbd "l") #'knot-list-view-list)
    (define-key map (kbd "r") #'knot-list-view-ready)
    (define-key map (kbd "b") #'knot-list-view-blocked)
    (define-key map (kbd "c") #'knot-list-view-closed)
    (define-key map (kbd "f") #'knot-list-filter)
    (define-key map (kbd "F") #'knot-list-clear-filters)
    (define-key map (kbd "RET") #'knot-list-show-at-point)
    (define-key map (kbd "s") #'knot-start)
    (define-key map (kbd "x") #'knot-close)
    (define-key map (kbd "D") #'knot-deps-transient)
    (define-key map (kbd "L") #'knot-links-transient)
    map)
  "Keymap for `knot-list-mode'.")

(defun knot-list-show-at-point ()
  "Open the show buffer for the ticket on the current list row.
Stashes the originating list buffer + row id (for `]'/`[')
and records the list buffer as the destination's back-buffer
(for `q')."
  (interactive)
  (unless (derived-mode-p 'knot-list-mode)
    (user-error "knot-list-show-at-point: not in a knot-list-mode buffer"))
  (let ((id (tabulated-list-get-id)))
    (unless id
      (user-error "knot-list: no ticket on this line"))
    (knot-show--open id (current-buffer) id (current-buffer))))

(define-derived-mode knot-list-mode tabulated-list-mode "Knot-List"
  "Major mode for the knot list buffer.

A single project-scoped buffer renders any of the list / ready /
blocked / closed views (see `knot-list--view').  `l', `r', `b',
`c' switch view in place; `f' opens the filter transient; `F'
clears active filters; `g' re-fetches.

\\{knot-list-mode-map}"
  (setq tabulated-list-format
        [("ID"       17 t)
         ("Status"   13 t)
         ("Pri"       4 t :right-align t)
         ("Mode"      5 t)
         ("Type"      9 t)
         ("Assignee" 10 t)
         ("AC"        5 t)
         ("Title"     0 t)])
  (setq tabulated-list-padding 1)
  (setq tabulated-list-sort-key (cons "ID" nil))
  ;; Free the header-line for view / filter status; column headers
  ;; render as the first row of the buffer body instead.
  (setq-local tabulated-list-use-header-line nil)
  (tabulated-list-init-header))

(defun knot-list--buffer-name (project)
  "Return the canonical list buffer name for PROJECT."
  (format "*knot-list: %s*" project))

(defun knot-list--ensure-buffer (info)
  "Return (or create) the list buffer for INFO, initialized in `knot-list-mode'."
  (let* ((project (knot-info--project-name info))
         (project-root (knot-info--project-root info))
         (buffer (get-buffer-create (knot-list--buffer-name project))))
    (with-current-buffer buffer
      (unless (derived-mode-p 'knot-list-mode)
        (knot-list-mode))
      (setq-local default-directory
                  (file-name-as-directory (or project-root default-directory))))
    buffer))

(defun knot-list--render ()
  "Refetch and redisplay the current buffer's view, preserving point.
Uses buffer-local `knot-list--view' and `knot-list--filters'.
Restores point to the row matching the previous row-id when
possible."
  (unless (derived-mode-p 'knot-list-mode)
    (user-error "knot-list--render: not in a knot-list-mode buffer"))
  (let* ((prev-id (tabulated-list-get-id))
         (args (knot-list--build-args knot-list--view knot-list--filters))
         (rows (knot-cli-call args)))
    (setq tabulated-list-entries (mapcar #'knot-list--row rows))
    (setq header-line-format
          (knot-list--header-line knot-list--view knot-list--filters))
    (tabulated-list-print t)
    (when prev-id
      (goto-char (point-min))
      (let ((found nil))
        (while (and (not found) (not (eobp)))
          (if (equal (tabulated-list-get-id) prev-id)
              (setq found t)
            (forward-line 1)))
        (unless found
          (goto-char (point-min)))))))

(defun knot-list--open (view)
  "Display the project's list buffer at VIEW.
Creates the buffer when absent; otherwise reuses it and switches
view in place (preserving active filters where the destination
view accepts them)."
  (let* ((info (knot-info-current))
         (buffer (knot-list--ensure-buffer info)))
    (with-current-buffer buffer
      (setq knot-list--view view)
      (knot-list--render))
    (pop-to-buffer-same-window buffer)))

;;;###autoload
(defun knot-list ()
  "Open the project's list buffer at the default `list' view."
  (interactive)
  (knot-list--open 'list))

(defun knot-list-view-list ()
  "Switch the current list buffer to the `list' view."
  (interactive)
  (knot-list--open 'list))

(defun knot-list-view-ready ()
  "Switch the current list buffer to the `ready' view."
  (interactive)
  (knot-list--open 'ready))

(defun knot-list-view-blocked ()
  "Switch the current list buffer to the `blocked' view."
  (interactive)
  (knot-list--open 'blocked))

(defun knot-list-view-closed ()
  "Switch the current list buffer to the `closed' view."
  (interactive)
  (knot-list--open 'closed))

(defun knot-list--row (row)
  "Build a tabulated-list entry from a single ROW alist."
  (let* ((id        (or (alist-get 'id row) ""))
         (title     (or (alist-get 'title row) ""))
         (status    (alist-get 'status row))
         (priority  (alist-get 'priority row))
         (mode      (alist-get 'mode row))
         (type      (alist-get 'type row))
         (assignee  (or (alist-get 'assignee row) ""))
         (ac-cell   (knot-list--ac-cell row)))
    (list id
          (vector (knot-format-propertize id 'knot-id)
                  (knot-format-status status)
                  (knot-format-priority priority)
                  (knot-format-mode mode)
                  (knot-format-type type)
                  assignee
                  ac-cell
                  title))))

(defun knot-list--ac-cell (row)
  "Return the AC column text for ROW.
Empty acceptance lists render as \"-\"."
  (let ((ac (alist-get 'acceptance row)))
    (if (and (listp ac) ac)
        (let ((total (length ac))
              (done  (cl-count-if (lambda (a) (alist-get 'done a)) ac)))
          (format "%d/%d" done total))
      "-")))


;;;; Filter transient (knot-list module)

(defun knot-list--current-filter-value (key)
  "Return the current buffer-local value for filter KEY, or nil."
  (cdr (assq key knot-list--filters)))

(defun knot-list--set-filter (key value)
  "Set filter KEY to VALUE in the current buffer's filter alist.
A nil or empty-string VALUE removes the entry."
  (setq knot-list--filters
        (assq-delete-all key knot-list--filters))
  (when (knot-list--filter-string-value value)
    (push (cons key value) knot-list--filters)))

(defun knot-list--read-from-allowed (prompt field current)
  "Prompt for one of FIELD's allowed values, defaulting to CURRENT."
  (let ((choices (cons "" (knot-info-allowed-values field))))
    (completing-read prompt choices nil nil nil nil (or current ""))))

(defun knot-list--read-free-string (prompt current)
  "Prompt for a free-form filter value, defaulting to CURRENT."
  (read-string prompt nil nil (or current "")))

(defun knot-list--read-limit (current)
  "Prompt for an integer limit, defaulting to CURRENT.
Empty input clears the limit."
  (let* ((raw (read-string "limit (empty to clear): "
                           nil nil (and current (format "%s" current)))))
    (cond
     ((or (null raw) (string-empty-p raw)) nil)
     ((string-match-p "\\`[0-9]+\\'" raw) raw)
     (t (user-error "knot-list: --limit must be a non-negative integer")))))

(defun knot-list--read-acceptance-complete (current)
  "Prompt for an --acceptance-complete value (`true' / `false' / unset)."
  (let* ((choices '("" "true" "false"))
         (default (or current "")))
    (let ((pick (completing-read
                 "acceptance-complete (empty to clear): "
                 choices nil t default)))
      (if (string-empty-p pick) nil pick))))

(defun knot-list-filter-set-mode ()
  "Prompt for `--mode' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'mode (knot-list--read-from-allowed
          "mode: " 'modes (knot-list--current-filter-value 'mode)))
  (knot-list--render))

(defun knot-list-filter-set-type ()
  "Prompt for `--type' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'type (knot-list--read-from-allowed
          "type: " 'types (knot-list--current-filter-value 'type)))
  (knot-list--render))

(defun knot-list-filter-set-status ()
  "Prompt for `--status' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'status (knot-list--read-from-allowed
            "status: " 'statuses (knot-list--current-filter-value 'status)))
  (knot-list--render))

(defun knot-list-filter-set-tag ()
  "Prompt for `--tag' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'tag (knot-list--read-free-string
         "tag: " (knot-list--current-filter-value 'tag)))
  (knot-list--render))

(defun knot-list-filter-set-assignee ()
  "Prompt for `--assignee' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'assignee (knot-list--read-free-string
              "assignee: " (knot-list--current-filter-value 'assignee)))
  (knot-list--render))

(defun knot-list-filter-set-limit ()
  "Prompt for `--limit' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'limit (knot-list--read-limit
           (knot-list--current-filter-value 'limit)))
  (knot-list--render))

(defun knot-list-filter-set-acceptance-complete ()
  "Prompt for `--acceptance-complete' and update the active filter."
  (interactive)
  (knot-list--set-filter
   'acceptance-complete
   (knot-list--read-acceptance-complete
    (knot-list--current-filter-value 'acceptance-complete)))
  (knot-list--render))

(defun knot-list-clear-filters ()
  "Clear every active filter on the current list buffer and re-render."
  (interactive)
  (setq knot-list--filters nil)
  (knot-list--render))

(transient-define-prefix knot-list-filter ()
  "Filter the active list view.

Each suffix prompts for the matching CLI filter value, updates
the buffer-local filter state, and re-renders.  `C' clears every
filter at once."
  ["Filter"
   ("m" "mode"                  knot-list-filter-set-mode)
   ("t" "type"                  knot-list-filter-set-type)
   ("s" "status"                knot-list-filter-set-status)
   ("T" "tag"                   knot-list-filter-set-tag)
   ("a" "assignee"              knot-list-filter-set-assignee)
   ("l" "limit"                 knot-list-filter-set-limit)
   ("A" "acceptance-complete"   knot-list-filter-set-acceptance-complete)]
  ["Other"
   ("C" "clear all"             knot-list-clear-filters)])


;;;; Show buffer (knot-show module)

(defvar-local knot-show--id nil
  "Ticket id rendered in this `knot-show-mode' buffer.")

(defvar-local knot-show--data nil
  "Parsed `knot show --json' data alist for the current buffer.")

(defvar-local knot-show--origin-list-buffer nil
  "Originating `knot-list-mode' buffer, when opened from a list row.
Nil when reached via a buttonized id (dep / link / parent / body
reference) or via `M-x knot-show'.  Used by `]'/`[' to step
through siblings.")

(defvar-local knot-show--origin-list-id nil
  "Originating list row id captured when this show buffer was opened.")

(defvar-local knot-show--back-buffer nil
  "Buffer `knot-show-quit' should switch to when this show buffer is dismissed.
Forms the back-button chain: each entry stores the buffer that
opened this one (a list buffer when entered via RET on a row, a
show buffer when reached via a buttonized id).  `]'/`[' step
laterally without growing the chain.  Nil falls through to
`quit-window'.")

(defun knot-show--buffer-name (project id)
  "Return the canonical show buffer name for PROJECT and ID."
  (format "*knot-show: %s · %s*" project id))

(defvar knot-show-mode-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map special-mode-map)
    (define-key map (kbd "?") #'knot)
    (define-key map (kbd "g") #'knot-refresh)
    (define-key map (kbd "q") #'knot-show-quit)
    (define-key map (kbd "RET") #'knot-show-RET)
    (define-key map (kbd "+") #'knot-show-add-ac)
    (define-key map (kbd "a") #'knot-show-add-ac)
    (define-key map (kbd "-") #'knot-show-remove-ac)
    (define-key map (kbd "k") #'knot-show-remove-at-point)
    (define-key map (kbd "]") #'knot-show-next-ticket)
    (define-key map (kbd "[") #'knot-show-prev-ticket)
    (define-key map (kbd ",") #'knot-update-from-show)
    (define-key map (kbd "s") #'knot-start)
    (define-key map (kbd "x") #'knot-close)
    (define-key map (kbd "e") #'knot-show-edit-description)
    (define-key map (kbd "d") #'knot-show-edit-design)
    (define-key map (kbd "b") #'knot-show-edit-body)
    (define-key map (kbd "n") #'knot-show-add-note)
    (define-key map (kbd "E") #'knot-show-edit-via-emacsclient)
    (define-key map (kbd "D") #'knot-deps-transient)
    (define-key map (kbd "L") #'knot-links-transient)
    (define-key map (kbd "p") #'backward-button)
    (define-key map (kbd "TAB") #'forward-button)
    (define-key map (kbd "<backtab>") #'backward-button)
    map)
  "Keymap for `knot-show-mode'.")

(declare-function markdown-gfm-checkbox-after-change-function "markdown-mode")

(define-derived-mode knot-show-mode markdown-view-mode "Knot-Show"
  "Major mode for the knot show buffer.

Renders one ticket with markdown fontification, buttonized
ticket ids, and a per-line keymap on acceptance criterion rows.

\\{knot-show-mode-map}"
  (setq-local truncate-lines nil)
  ;; `markdown-mode' installs an after-change hook that buttonises
  ;; every GFM `- [ ]' / `- [x]' as its own task-list button.  Those
  ;; would shadow `knot-show-RET' on AC rows.  AC interaction is
  ;; owned by `knot-show-flip-ac' via the `knot-ac-title' text
  ;; property, so remove the hook for this buffer's lifetime.
  (remove-hook 'after-change-functions
               #'markdown-gfm-checkbox-after-change-function t)
  (setq buffer-read-only t))

(defun knot-show--ac-at-point ()
  "Return the `knot-ac-title' text property at point, or nil."
  (get-text-property (point) 'knot-ac-title))

(defun knot-show-quit ()
  "Back-button for the show buffer.

Switches to `knot-show--back-buffer' when set and live, falling
back to `quit-window' otherwise.  The back-buffer chain is built
on entry: opening a show buffer from a list row records the list
buffer; drilling in via a buttonized id records the originating
show buffer; `]'/`[' propagate the existing back-buffer without
growing the chain."
  (interactive)
  (let ((back knot-show--back-buffer))
    (if (and back (buffer-live-p back))
        (switch-to-buffer back)
      (quit-window))))

(defconst knot-show--field->command
  '((status   . knot-update-set-status)
    (type     . knot-update-set-type)
    (priority . knot-update-set-priority)
    (mode     . knot-update-set-mode)
    (assignee . knot-update-set-assignee)
    (tags     . knot-update-set-tags)
    (parent   . knot-update-set-parent))
  "Mapping from `knot-field' value-span symbols to update commands.
`knot-show-RET' looks up the `knot-field' text property at point
and dispatches to the matching `knot-update-set-*' command — the
RET-on-frontmatter-field path that complements the `,' transient.
Symbols match the keys passed to `knot-show--insert-field'.")

(defun knot-show-RET ()
  "Dispatch RET in a show buffer.

Precedence:
1. A `knot-id' button at point wins (drills into that ticket).
2. An acceptance-criterion line at point flips its done state.
3. Any other button at point is pushed.
4. A `knot-field' value span (status, type, priority, mode,
   assignee, tags, parent) dispatches to the matching
   `knot-update-set-*' command via `knot-show--field->command'.
5. Otherwise, errors with `nothing actionable at point'.

Step 4 only fires when no button overlaps the value span, so the
parent line's RET still drills into the buttonized id; use `,P'
to edit parent."
  (interactive)
  (let ((btn   (button-at (point)))
        (field (get-text-property (point) 'knot-field)))
    (cond
     ((and btn (button-get btn 'knot-id))
      (push-button (point)))
     ((knot-show--ac-at-point)
      (knot-show-flip-ac))
     (btn
      (push-button (point)))
     (field
      (let ((cmd (alist-get field knot-show--field->command)))
        (if cmd
            (call-interactively cmd)
          (user-error "knot-show: no update command for field %s" field))))
     (t
      (user-error "knot-show: nothing actionable at point")))))

(defun knot-show-flip-ac ()
  "Flip the acceptance criterion at point via `knot update --ac'."
  (interactive)
  (let ((title (knot-show--ac-at-point)))
    (unless title
      (user-error "knot-show: not on an acceptance criterion line"))
    (let* ((entry (cl-find-if (lambda (a) (equal title (alist-get 'title a)))
                              (alist-get 'acceptance knot-show--data)))
           (done (and entry (alist-get 'done entry))))
      (knot-cli-call (list "update" knot-show--id
                           "--ac" title
                           (if done "--undone" "--done")))
      (knot--after-mutation))))

(defun knot-show-add-ac ()
  "Prompt for a new acceptance criterion and add it via `--add-ac'."
  (interactive)
  (unless knot-show--id
    (user-error "knot-show: no ticket in this buffer"))
  (let ((title (read-string "New acceptance criterion: ")))
    (when (or (null title) (string-empty-p (string-trim title)))
      (user-error "knot-show: empty criterion title"))
    (knot-cli-call (list "update" knot-show--id "--add-ac" title))
    (knot--after-mutation)))

(defun knot-show-remove-ac ()
  "Remove the acceptance criterion at point via `--remove-ac' after confirmation."
  (interactive)
  (let ((title (knot-show--ac-at-point)))
    (unless title
      (user-error "knot-show: not on an acceptance criterion line"))
    (when (yes-or-no-p (format "Remove acceptance criterion %S? " title))
      (knot-cli-call (list "update" knot-show--id "--remove-ac" title))
      (knot--after-mutation))))

(defun knot-show--dep-id-at-point ()
  "Return the `knot-dep-id' text property at point, or nil."
  (get-text-property (point) 'knot-dep-id))

(defun knot-show--rdep-id-at-point ()
  "Return the `knot-rdep-id' text property at point, or nil.

`knot-rdep-id' rows live in the `## Blocking' section: each row's
id names a ticket that depends on this one, so removing the
relationship runs `knot undep' with the arguments swapped relative
to a `## Blockers' row."
  (get-text-property (point) 'knot-rdep-id))

(defun knot-show--link-id-at-point ()
  "Return the `knot-link-id' text property at point, or nil."
  (get-text-property (point) 'knot-link-id))

(defun knot-show-remove-at-point ()
  "Remove the relationship or AC at point after confirmation.

Dispatches on the text properties added during render: a dep row
in `## Blockers' (`knot-dep-id') triggers `knot undep <this>
<row>'; a reverse-dep row in `## Blocking' (`knot-rdep-id')
triggers `knot undep <row> <this>'; a link row in `## Linked'
\(`knot-link-id') triggers `knot unlink'; an acceptance-criterion
line (`knot-ac-title') triggers `--remove-ac'.  Errors when none
of those properties is at point."
  (interactive)
  (let ((dep-id   (knot-show--dep-id-at-point))
        (rdep-id  (knot-show--rdep-id-at-point))
        (link-id  (knot-show--link-id-at-point))
        (ac       (knot-show--ac-at-point))
        (id       (knot-update--ticket-id)))
    (cond
     (dep-id
      (when (yes-or-no-p (format "Remove dep %s from %s? " dep-id id))
        (knot-cli-call (list "undep" id dep-id))
        (knot--after-mutation)))
     (rdep-id
      (when (yes-or-no-p
             (format "Remove dep %s from %s? " id rdep-id))
        (knot-cli-call (list "undep" rdep-id id))
        (knot--after-mutation)))
     (link-id
      (when (yes-or-no-p (format "Remove link %s ↔ %s? " id link-id))
        (knot-cli-call (list "unlink" id link-id))
        (knot--after-mutation)))
     (ac
      (when (yes-or-no-p (format "Remove acceptance criterion %S? " ac))
        (knot-cli-call (list "update" id "--remove-ac" ac))
        (knot--after-mutation)))
     (t
      (user-error
       "knot-show: not on a dep, link, or acceptance criterion line")))))

(defun knot-show--step (direction)
  "Move to the row DIRECTION away in the originating list buffer.
Propagates the existing `knot-show--back-buffer' to the next
buffer so lateral stepping does not grow the back chain."
  (unless (and knot-show--origin-list-buffer
               (buffer-live-p knot-show--origin-list-buffer))
    (user-error "knot-show: no originating list buffer for ]/["))
  (let ((origin-buf knot-show--origin-list-buffer)
        (origin-id  knot-show--origin-list-id)
        (back       knot-show--back-buffer)
        (next-id nil))
    (with-current-buffer origin-buf
      (save-excursion
        (goto-char (point-min))
        (let ((found nil))
          (while (and (not found) (not (eobp)))
            (if (equal (tabulated-list-get-id) origin-id)
                (setq found t)
              (forward-line 1)))
          (when found
            (forward-line direction)
            (unless (or (eobp)
                        (and (< direction 0)
                             (equal (tabulated-list-get-id) origin-id)))
              (setq next-id (tabulated-list-get-id)))))))
    (unless next-id
      (user-error "knot-show: no %s ticket in originating list"
                  (if (> direction 0) "next" "previous")))
    (knot-show--open next-id origin-buf next-id back)))

(defun knot-show-next-ticket ()
  "Open the next ticket from the originating list buffer."
  (interactive)
  (knot-show--step 1))

(defun knot-show-prev-ticket ()
  "Open the previous ticket from the originating list buffer."
  (interactive)
  (knot-show--step -1))

(defun knot-show--render-relationship (label entries &optional relation-prop)
  "Render a markdown section LABEL listing relationship ENTRIES.

When RELATION-PROP is non-nil, every row is propertized with that
symbol carrying the row's id, so commands at point (e.g. `k' for
undep / unlink) can identify which relationship they target."
  (when (and entries (listp entries) (not (null entries)))
    (insert (format "## %s\n\n" label))
    (dolist (entry entries)
      (let* ((id     (alist-get 'id entry))
             (title  (alist-get 'title entry))
             (status (alist-get 'status entry))
             (start  (point)))
        (insert (format "- %s [%s] %s\n"
                        (or id "")
                        (or status "?")
                        (or title "")))
        (when (and relation-prop id)
          (add-text-properties start (point)
                               (list relation-prop id)))))
    (insert "\n")))

(defun knot-show--render-scalar-list (label items)
  "Insert a `**LABEL:** csv' line for ITEMS when ITEMS is a non-empty list.
Non-editable: no `knot-field' property is added.  Tags are
rendered separately via `knot-show--insert-field' so RET can edit
the list as a whole."
  (when (and items (listp items) (not (null items)))
    (insert (format "**%s:** %s\n" label (string-join items ", ")))))

(defun knot-show--insert-field (field value)
  "Insert VALUE at point and annotate its character span with `knot-field' FIELD.
VALUE is coerced to a string (numbers stringified, nil rendered
as \"-\").  The value's span carries the `knot-field' text
property only; surrounding `**label:**' markup is left
unannotated so RET there falls through to the no-op user-error."
  (let ((p (point)))
    (insert (cond ((stringp value) value)
                  ((numberp value) (number-to-string value))
                  ((null value) "-")
                  (t (format "%s" value))))
    (add-text-properties p (point) (list 'knot-field field))))

(defun knot-show--render (data)
  "Render DATA (the parsed `show' envelope) into the current buffer."
  (let ((inhibit-read-only t))
    (erase-buffer)
    (let* ((id        (alist-get 'id data))
           (title     (alist-get 'title data))
           (status    (alist-get 'status data))
           (type      (alist-get 'type data))
           (priority  (alist-get 'priority data))
           (mode      (alist-get 'mode data))
           (parent    (alist-get 'parent data))
           (assignee  (alist-get 'assignee data))
           (tags      (alist-get 'tags data))
           (deps      (alist-get 'deps data))
           (links     (alist-get 'links data))
           (external  (alist-get 'external_refs data))
           (created   (alist-get 'created data))
           (updated   (alist-get 'updated data))
           (acceptance (alist-get 'acceptance data))
           (body      (alist-get 'body data))
           (blockers  (alist-get 'blockers data))
           (blocking  (alist-get 'blocking data))
           (children  (alist-get 'children data))
           (linked    (alist-get 'linked data)))
      (insert (format "# %s\n\n" (or title "")))
      (insert (format "**id:** %s\n" (or id "")))
      (insert "**status:** ")
      (knot-show--insert-field 'status (or status "-"))
      (insert " · **type:** ")
      (knot-show--insert-field 'type (or type "-"))
      (insert " · **priority:** ")
      (knot-show--insert-field 'priority
                               (if (numberp priority) priority "-"))
      (insert " · **mode:** ")
      (knot-show--insert-field 'mode (or mode "-"))
      (insert "\n")
      (when (and assignee (not (string-empty-p assignee)))
        (insert "**assignee:** ")
        (knot-show--insert-field 'assignee assignee)
        (insert "\n"))
      (when (and parent (stringp parent) (not (string-empty-p parent)))
        (insert "**parent:** ")
        (knot-show--insert-field 'parent parent)
        (insert "\n"))
      (when (and tags (listp tags) tags)
        (insert "**tags:** ")
        (knot-show--insert-field 'tags (string-join tags ", "))
        (insert "\n"))
      (knot-show--render-scalar-list "deps" deps)
      (knot-show--render-scalar-list "links" links)
      (knot-show--render-scalar-list "external" external)
      (when created (insert (format "**created:** %s\n" created)))
      (when updated (insert (format "**updated:** %s\n" updated)))
      (insert "\n")
      (insert "## Acceptance Criteria\n\n")
      (if (and acceptance (listp acceptance) acceptance)
          (dolist (ac acceptance)
            (let* ((ac-title (alist-get 'title ac))
                   (done     (alist-get 'done ac))
                   (start    (point)))
              (insert (format "- [%s] %s\n"
                              (if done "x" " ")
                              (or ac-title "")))
              (add-text-properties start (point)
                                   (list 'knot-ac-title ac-title
                                         'knot-ac-done done))))
        (insert "_(no acceptance criteria)_\n"))
      (insert "\n")
      (when (and body (stringp body) (not (string-empty-p body)))
        (insert body)
        (unless (string-suffix-p "\n" body)
          (insert "\n"))
        (insert "\n"))
      (knot-show--render-relationship "Blockers" blockers 'knot-dep-id)
      (knot-show--render-relationship "Blocking" blocking 'knot-rdep-id)
      (knot-show--render-relationship "Children" children)
      (knot-show--render-relationship "Linked" linked 'knot-link-id))
    (knot-id-buttonize-region (point-min) (point-max))))

(defun knot-show--open (id &optional origin-list-buffer origin-id back-buffer)
  "Open the show buffer for ID, reusing it when one already exists.

When ORIGIN-LIST-BUFFER is non-nil, the originating list buffer
and ORIGIN-ID (defaulting to ID) are stashed as buffer-locals so
`]'/`[' can step through siblings.  Drilling in via a buttonized
id from another show buffer passes neither, preserving the
existing stash.

When BACK-BUFFER is non-nil and not the destination buffer
itself, it is recorded as `knot-show--back-buffer' so
`knot-show-quit' (bound to `q') walks the entry chain.  Passing
the destination buffer (e.g. re-opening the same id) is treated
as nil to avoid self-loops."
  (let* ((info (knot-info-current))
         (project (knot-info--project-name info))
         (project-root (knot-info--project-root info))
         (data (knot-cli-call (list "show" id)))
         (real-id (or (alist-get 'id data) id))
         (buf-name (knot-show--buffer-name project real-id))
         (buffer (get-buffer-create buf-name)))
    (with-current-buffer buffer
      (unless (derived-mode-p 'knot-show-mode)
        (knot-show-mode))
      (setq-local default-directory
                  (file-name-as-directory (or project-root default-directory)))
      (setq knot-show--id real-id)
      (setq knot-show--data data)
      (when origin-list-buffer
        (setq knot-show--origin-list-buffer origin-list-buffer
              knot-show--origin-list-id (or origin-id real-id)))
      (when (and back-buffer
                 (buffer-live-p back-buffer)
                 (not (eq back-buffer buffer)))
        (setq knot-show--back-buffer back-buffer))
      (knot-show--render data)
      (goto-char (point-min)))
    (pop-to-buffer-same-window buffer)
    buffer))

(defun knot-show--refresh ()
  "Re-fetch and re-render the current show buffer's ticket.
Preserves origin-list locals and point (clamped to buffer end)."
  (unless (derived-mode-p 'knot-show-mode)
    (user-error "knot-show--refresh: not in a knot-show-mode buffer"))
  (let* ((id knot-show--id)
         (data (knot-cli-call (list "show" id)))
         (pt (point)))
    (setq knot-show--data data)
    (knot-show--render data)
    (goto-char (min pt (point-max)))))

;;;###autoload
(defun knot-show (id)
  "Open the show buffer for ticket ID.
ID may be a partial id, resolved by the CLI."
  (interactive (list (read-string "id: ")))
  (knot-show--open id))


;;;; Capture buffers (knot-capture module)

(defvar-local knot-capture--id nil
  "Ticket id this capture buffer commits to.")

(defvar-local knot-capture--field nil
  "Symbol naming the field this capture buffer commits to.
One of `description', `design', `body', or `note'.")

(defvar-local knot-capture--callback nil
  "Thunk called after a successful commit; nil to skip.
Used to refresh the originating show buffer.")

(defconst knot-capture--field->flag
  '((description . "--description")
    (design      . "--design")
    (body        . "--body"))
  "Mapping from capture-buffer field symbols to `knot update' flags.
Note: `note' commits via `knot add-note', not `knot update'.")

(defconst knot-capture--field->label
  '((description . "edit-description")
    (design      . "edit-design")
    (body        . "edit-body")
    (note        . "add-note"))
  "Mapping from capture-buffer field symbols to buffer-name labels.")

(defvar knot-capture-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "C-c C-c") #'knot-capture-commit)
    (define-key map (kbd "C-c C-k") #'knot-capture-discard)
    map)
  "Keymap for `knot-capture-mode'.")

(define-minor-mode knot-capture-mode
  "Minor mode for knot long-form capture buffers.

Carries the target ticket id, field, and post-commit callback as
buffer-locals.  `C-c C-c' commits the buffer contents to the
target ticket via `knot update --<field> ...' (or, for the `note'
field, pipes stdin to `knot add-note <id>').  `C-c C-k' discards
without writing.  Buffer-contents are passed as a single argv
element with no shell escaping."
  :lighter " Knot-Capture"
  :keymap knot-capture-mode-map)

(defun knot-capture--buffer-name (project id field)
  "Return the canonical capture buffer name for PROJECT, ID, and FIELD."
  (let ((label (or (alist-get field knot-capture--field->label)
                   (symbol-name field))))
    (format "*knot-%s: %s · %s*" label project id)))

(defun knot-capture--extract-section (body section)
  "Extract the SECTION subtree from a markdown BODY string.
SECTION is a heading name (e.g. \"Description\").  Returns the
content between `## SECTION' and the next `^## ' header, with
trailing whitespace trimmed.  Returns the empty string when
SECTION is not present."
  (if (and body (stringp body))
      (let ((case-fold-search nil)
            (pattern (concat "^## " (regexp-quote section) "[ \t]*\n+")))
        (if (string-match pattern body)
            (let* ((start (match-end 0))
                   (rest  (substring body start))
                   (end   (if (string-match "^## " rest)
                              (match-beginning 0)
                            (length rest))))
              (string-trim-right (substring rest 0 end)))
          ""))
    ""))

(defun knot-capture--make-refresh-callback (origin)
  "Return a thunk that refreshes ORIGIN's project after a capture commit.

ORIGIN is the show buffer the capture was launched from.  The
thunk re-enters ORIGIN (when still live) so the cross-buffer
walker sees the right `default-directory' / project scope, then
propagates to every visible knot.el buffer in the same project."
  (lambda ()
    (when (and origin (buffer-live-p origin))
      (with-current-buffer origin
        (when (derived-mode-p 'knot-show-mode)
          (knot--after-mutation))))))

(defun knot-capture--open (id field prefill callback)
  "Pop a capture buffer for ID/FIELD prefilled with PREFILL.
CALLBACK is a thunk called after a successful commit."
  (let* ((info         (knot-info-current))
         (project      (knot-info--project-name info))
         (project-root (knot-info--project-root info))
         (buf-name     (knot-capture--buffer-name project id field))
         (buffer       (get-buffer-create buf-name))
         (label        (or (alist-get field knot-capture--field->label)
                           (symbol-name field))))
    (with-current-buffer buffer
      (let ((inhibit-read-only t))
        (erase-buffer)
        (when (and prefill (stringp prefill) (not (string-empty-p prefill)))
          (insert prefill)))
      (setq-local default-directory
                  (file-name-as-directory
                   (or project-root default-directory)))
      (unless (derived-mode-p 'markdown-mode)
        (markdown-mode))
      (knot-capture-mode 1)
      (setq knot-capture--id id
            knot-capture--field field
            knot-capture--callback callback)
      (set-buffer-modified-p nil)
      (goto-char (point-min))
      (setq header-line-format
            (format "knot · %s · %s · C-c C-c commit · C-c C-k cancel"
                    label id)))
    (pop-to-buffer buffer)
    buffer))

(defun knot-capture-commit ()
  "Commit the current capture buffer to the target ticket."
  (interactive)
  (unless knot-capture-mode
    (user-error "knot-capture: not in a knot-capture buffer"))
  (let* ((id       knot-capture--id)
         (field    knot-capture--field)
         (callback knot-capture--callback)
         (content  (buffer-substring-no-properties (point-min) (point-max))))
    (unless (and id (stringp id) (not (string-empty-p id)))
      (user-error "knot-capture: no target id in this buffer"))
    (pcase field
      ('note
       (when (string-empty-p (string-trim content))
         (user-error "knot-capture: empty note (use C-c C-k to discard)"))
       (knot-cli-call (list "add-note" id) content))
      ((or 'description 'design 'body)
       (let ((flag (alist-get field knot-capture--field->flag)))
         (knot-cli-call (list "update" id flag content))))
      (_ (user-error "knot-capture: unknown field %S" field)))
    (set-buffer-modified-p nil)
    (let ((buffer (current-buffer)))
      (quit-window t)
      (when (buffer-live-p buffer)
        (kill-buffer buffer)))
    (when (functionp callback)
      (funcall callback))))

(defun knot-capture-discard ()
  "Discard the current capture buffer without writing."
  (interactive)
  (unless knot-capture-mode
    (user-error "knot-capture: not in a knot-capture buffer"))
  (set-buffer-modified-p nil)
  (let ((buffer (current-buffer)))
    (quit-window t)
    (when (buffer-live-p buffer)
      (kill-buffer buffer))))


;;;; Show-buffer capture entry points

(defun knot-show--field-prefill (field)
  "Return the prefill content for FIELD from the show buffer's data.
For `body', the entire body field is returned verbatim; for
`description' and `design', the corresponding `## Section'
subtree is extracted."
  (let ((body (alist-get 'body knot-show--data)))
    (pcase field
      ('body        (or body ""))
      ('description (knot-capture--extract-section body "Description"))
      ('design      (knot-capture--extract-section body "Design"))
      (_ ""))))

(defun knot-show--open-capture (field)
  "Open a capture buffer for FIELD on the current show buffer's ticket."
  (let* ((id (knot-update--ticket-id))
         (prefill (knot-show--field-prefill field))
         (origin (current-buffer)))
    (knot-capture--open id field prefill
                        (knot-capture--make-refresh-callback origin))))

(defun knot-show-edit-description ()
  "Pop a capture buffer to edit this ticket's Description section.
Prefilled with the current `## Description' subtree; `C-c C-c'
commits via `knot update --description ...'."
  (interactive)
  (knot-show--open-capture 'description))

(defun knot-show-edit-design ()
  "Pop a capture buffer to edit this ticket's Design section.
Prefilled with the current `## Design' subtree; `C-c C-c'
commits via `knot update --design ...'."
  (interactive)
  (knot-show--open-capture 'design))

(defun knot-show-edit-body ()
  "Pop a capture buffer to edit this ticket's full body.
Prefilled with the current body; `C-c C-c' commits via
`knot update --body ...'.  Destructive (no `--force'); use git
to recover."
  (interactive)
  (knot-show--open-capture 'body))

(defun knot-show-add-note ()
  "Pop a capture buffer to append a note to the current ticket.
`C-c C-c' pipes the buffer contents via stdin to
`knot add-note <id>'."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (origin (current-buffer)))
    (knot-capture--open id 'note ""
                        (knot-capture--make-refresh-callback origin))))

(defun knot-show-edit-via-emacsclient ()
  "Escape hatch: shell out to `knot edit <id>' with `EDITOR=emacsclient'.

Runs `knot edit' asynchronously with the child process's
`EDITOR' and `VISUAL' set to `knot-emacsclient-executable'.  The
ticket file opens in the running Emacs server; on `C-x #'
(`server-edit') the child process exits and this show buffer is
refreshed.  Requires the Emacs server to be running
(`server-start')."
  (interactive)
  (let* ((id     (knot-update--ticket-id))
         (origin (current-buffer)))
    (unless (and (fboundp 'server-running-p) (server-running-p))
      (user-error
       "knot-show: start the Emacs server first (M-x server-start) for `E'"))
    (let* ((program     (knot-cli--program))
           (emacsclient (or (executable-find knot-emacsclient-executable)
                            knot-emacsclient-executable))
           (process-environment
            (cons (concat "EDITOR=" emacsclient)
                  (cons (concat "VISUAL=" emacsclient)
                        process-environment))))
      (make-process
       :name (format "knot-edit-%s" id)
       :command (list program "edit" id)
       :buffer (generate-new-buffer
                (format " *knot-edit-%s*" id))
       :noquery t
       :sentinel
       (lambda (proc _event)
         (when (memq (process-status proc) '(exit signal))
           (let ((pbuf (process-buffer proc)))
             (when (buffer-live-p pbuf) (kill-buffer pbuf)))
           (when (and origin (buffer-live-p origin))
             (with-current-buffer origin
               (when (derived-mode-p 'knot-show-mode)
                 (knot--after-mutation))))))))))


;;;; Update transient (knot-update module)

(defun knot-update--ticket-id ()
  "Return the ticket id in the current show buffer, or signal `user-error'."
  (unless (derived-mode-p 'knot-show-mode)
    (user-error "knot-update: not in a knot-show-mode buffer"))
  (unless (and knot-show--id (not (string-empty-p knot-show--id)))
    (user-error "knot-update: no ticket id in this buffer"))
  knot-show--id)

(defun knot-update--current-field (field)
  "Return the current value for FIELD from the show buffer's parsed data."
  (alist-get field knot-show--data))

(defun knot-update--commit (id flag value)
  "Run `knot update ID FLAG VALUE' atomically, then refresh.
A single subprocess per call — no batching.  Errors raised by the
CLI envelope propagate out of `knot-cli-call' as `user-error',
which short-circuits the refresh and leaves buffer state intact.
Refresh propagates to every visible knot.el buffer for this
project via `knot--after-mutation'."
  (knot-cli-call (list "update" id flag value))
  (knot--after-mutation))

(defun knot-update--read-allowed (prompt field current)
  "Prompt for FIELD's allowed value, defaulting to CURRENT.
FIELD names an entry in `allowed_values' (e.g. `statuses',
`types', `modes')."
  (let ((choices (knot-info-allowed-values field))
        (default (and current (not (string-empty-p (format "%s" current)))
                      (format "%s" current))))
    (completing-read prompt choices nil t nil nil default)))

(defun knot-update--read-priority (current)
  "Prompt for a priority within `allowed_values.priority_range', default CURRENT."
  (let* ((range (knot-info-allowed-values 'priority_range))
         (min (alist-get 'min range))
         (max (alist-get 'max range))
         (choices (when (and (numberp min) (numberp max))
                    (mapcar #'number-to-string (number-sequence min max))))
         (default (and (numberp current) (number-to-string current)))
         (raw (completing-read
               (format "priority (%s..%s): " (or min "?") (or max "?"))
               choices nil t nil nil default)))
    (unless (string-match-p "\\`[0-9]+\\'" raw)
      (user-error "knot-update: priority must be a non-negative integer"))
    raw))

(defun knot-update--read-tags (current)
  "Prompt for a comma-list of tags, defaulting to CURRENT (a list of strings).
Empty input clears the tag list."
  (let ((default (and (listp current) current (string-join current ","))))
    (read-string "tags (comma-list, empty to clear): " default)))

(defun knot-update--read-free-string (prompt current)
  "Prompt for a free-form value with CURRENT as the default text."
  (read-string prompt (and current (not (string-empty-p (format "%s" current)))
                           (format "%s" current))))

(defun knot-update--read-parent (current &optional include-closed)
  "Prompt for a parent id with completion over tickets.
Candidates are formatted `id  title' and selection extracts the
id.  The minibuffer pre-fills with CURRENT so plain RET keeps the
existing parent; deleting the text and RET clears it.  By
default only live tickets are offered; with INCLUDE-CLOSED
non-nil, closed tickets are appended after the live set."
  (let* ((live (knot-cli-call '("list")))
         (closed (and include-closed
                      (knot-cli-call '("list" "--status" "closed"))))
         (rows (append live closed))
         (choices (mapcar (lambda (r)
                            (format "%s  %s"
                                    (or (alist-get 'id r) "")
                                    (or (alist-get 'title r) "")))
                          rows))
         (initial (and current (not (string-empty-p (format "%s" current)))
                       (format "%s" current)))
         (prompt (if include-closed
                     "parent (live + closed, empty to clear): "
                   "parent (live, C-u to include closed, empty to clear): "))
         (pick (completing-read prompt choices nil nil initial)))
    (cond
     ((or (null pick) (string-empty-p pick)) "")
     ((string-match "\\`\\(\\S-+\\)" pick) (match-string 1 pick))
     (t pick))))

(defun knot-update-set-status ()
  "Replace status via `knot update --status'."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-allowed
                 "status: " 'statuses
                 (knot-update--current-field 'status))))
    (knot-update--commit id "--status" value)))

(defun knot-update-set-priority ()
  "Replace priority via `knot update --priority'."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-priority
                 (knot-update--current-field 'priority))))
    (knot-update--commit id "--priority" value)))

(defun knot-update-set-mode ()
  "Replace mode via `knot update --mode'."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-allowed
                 "mode: " 'modes
                 (knot-update--current-field 'mode))))
    (knot-update--commit id "--mode" value)))

(defun knot-update-set-type ()
  "Replace type via `knot update --type'."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-allowed
                 "type: " 'types
                 (knot-update--current-field 'type))))
    (knot-update--commit id "--type" value)))

(defun knot-update-set-tags ()
  "Replace the tag list via `knot update --tags'.
Empty input clears the tag list."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-tags
                 (knot-update--current-field 'tags))))
    (knot-update--commit id "--tags" value)))

(defun knot-update-set-assignee ()
  "Set or clear assignee via `knot update --assignee'.
Empty input clears the assignee."
  (interactive)
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-free-string
                 "assignee (empty to clear): "
                 (knot-update--current-field 'assignee))))
    (knot-update--commit id "--assignee" value)))

(defun knot-update-set-parent (&optional include-closed)
  "Set or clear parent via `knot update --parent'.
Completion offers live tickets by default; with a prefix argument
\(\\[universal-argument]\\), closed tickets are appended to the
candidate list.  Empty input clears the parent id."
  (interactive "P")
  (let* ((id (knot-update--ticket-id))
         (value (knot-update--read-parent
                 (knot-update--current-field 'parent)
                 include-closed)))
    (knot-update--commit id "--parent" value)))

(transient-define-prefix knot-update-from-show ()
  "Update the current show buffer's ticket atomically.

Each suffix prompts for one value and commits a single
`knot update --flag value' subprocess; the show buffer
auto-refreshes on success.  CLI errors raise `user-error' in the
minibuffer and leave buffer state unchanged."
  ["Update"
   ("s" "status"   knot-update-set-status)
   ("p" "priority" knot-update-set-priority)
   ("m" "mode"     knot-update-set-mode)
   ("t" "type"     knot-update-set-type)
   ("T" "tags"     knot-update-set-tags)
   ("a" "assignee" knot-update-set-assignee)
   ("P" "parent"   knot-update-set-parent)])


;;;; Create transient (knot-create module)
;;
;; Single transient prefix `knot-create' with infix args for every
;; create-time flag except title.  Title is prompted in the minibuffer
;; at commit time so the transient stays focused on options.  Repeatable
;; flags (--dep, --link, --acceptance, --external-ref) are surfaced as
;; comma-list infixes and expanded into multiple argv pairs at commit.
;;
;; Reader defaults are sourced from `knot info --json' (allowed_values
;; and defaults), keeping the transient in sync with the project's
;; configured values.

(defun knot-create--read-allowed (field default-key)
  "Build a `:reader' over FIELD's allowed values, defaulting via DEFAULT-KEY.
The returned closure has the `(prompt initial-input history)' signature
that transient `:reader' expects."
  (lambda (prompt initial-input history)
    (let* ((choices (knot-info-allowed-values field))
           (default (knot-info-defaults default-key))
           (default-str (and default (format "%s" default))))
      (completing-read prompt choices nil t initial-input history
                       default-str))))

(defun knot-create--read-priority (prompt initial-input history)
  "`:reader' for the priority infix, defaulting to `default_priority'.
Accepts only a non-negative integer inside `priority_range'."
  (let* ((range (knot-info-allowed-values 'priority_range))
         (min (alist-get 'min range))
         (max (alist-get 'max range))
         (choices (when (and (numberp min) (numberp max))
                    (mapcar #'number-to-string (number-sequence min max))))
         (default (knot-info-defaults 'default_priority))
         (default-str (and (numberp default) (number-to-string default)))
         (raw (completing-read
               (or prompt (format "priority (%s..%s): " (or min "?") (or max "?")))
               choices nil t initial-input history default-str)))
    (unless (string-match-p "\\`[0-9]+\\'" raw)
      (user-error "knot-create: priority must be a non-negative integer"))
    raw))

(defun knot-create--read-tags (prompt _initial-input _history)
  "`:reader' for the tags infix: a comma-list of tag names, empty allowed."
  (read-string (or prompt "tags (comma-list): ")))

(defun knot-create--read-assignee (prompt _initial-input _history)
  "`:reader' for the assignee infix."
  (read-string (or prompt "assignee: ")))

(defun knot-create--read-parent (_prompt _initial-input _history)
  "`:reader' for the parent infix.
Delegates to `knot-update--read-parent' for completing-read across
live + closed tickets.  Empty selection is allowed;
`knot-create--expand-args' drops empty values so no `--parent' flag
reaches the CLI."
  (knot-update--read-parent nil t))

(defun knot-create--read-id-list (label)
  "Return a `:reader' closure picking multiple live ticket ids via CRM.

Candidates are `id  title' rows from `knot list' (live only).  The
user picks any number, comma-separated, with completion at each
token; the closure returns a comma-list of the picked ids (the
`  title' suffix is stripped).  Empty input is allowed and the
empty value gets dropped by `knot-create--expand-args' so no flag
reaches the CLI.

Initial-input is ignored — re-press the infix to redo the picks
(transient stores only the comma-list of ids, which is not a valid
prefill for a `id  title'-candidate completion)."
  (lambda (prompt _initial-input _history)
    (let ((choices (knot-deps--live-choices)))
      (when (null choices)
        (user-error "knot-create: no live tickets to choose from"))
      (let ((picks (completing-read-multiple
                    (or prompt
                        (format "%s (TAB completes, comma-separated): " label))
                    choices nil t)))
        (mapconcat #'knot-deps--extract-id picks ",")))))

(defun knot-create--read-acceptance (prompt _initial-input _history)
  "`:reader' for the acceptance infix: a comma-list of AC titles."
  (read-string (or prompt "acceptance titles (comma-list): ")))

(defun knot-create--read-external-ref (prompt _initial-input _history)
  "`:reader' for the external-ref infix: a comma-list of refs."
  (read-string (or prompt "external refs (comma-list): ")))

(transient-define-infix knot-create:--type ()
  :description "type"
  :class 'transient-option
  :argument "--type="
  :reader (knot-create--read-allowed 'types 'default_type))

(transient-define-infix knot-create:--priority ()
  :description "priority"
  :class 'transient-option
  :argument "--priority="
  :reader #'knot-create--read-priority)

(transient-define-infix knot-create:--mode ()
  :description "mode"
  :class 'transient-option
  :argument "--mode="
  :reader (knot-create--read-allowed 'modes 'default_mode))

(transient-define-infix knot-create:--tags ()
  :description "tags (comma)"
  :class 'transient-option
  :argument "--tags="
  :reader #'knot-create--read-tags)

(transient-define-infix knot-create:--assignee ()
  :description "assignee"
  :class 'transient-option
  :argument "--assignee="
  :reader #'knot-create--read-assignee)

(transient-define-infix knot-create:--parent ()
  :description "parent"
  :class 'transient-option
  :argument "--parent="
  :reader #'knot-create--read-parent)

(transient-define-infix knot-create:--dep ()
  :description "deps (TAB completes)"
  :class 'transient-option
  :argument "--dep="
  :reader (knot-create--read-id-list "deps"))

(transient-define-infix knot-create:--link ()
  :description "links (TAB completes)"
  :class 'transient-option
  :argument "--link="
  :reader (knot-create--read-id-list "links"))

(transient-define-infix knot-create:--acceptance ()
  :description "acceptance (comma)"
  :class 'transient-option
  :argument "--acceptance="
  :reader #'knot-create--read-acceptance)

(transient-define-infix knot-create:--external-ref ()
  :description "external refs (comma)"
  :class 'transient-option
  :argument "--external-ref="
  :reader #'knot-create--read-external-ref)

(defconst knot-create--repeatable-flags
  '("--dep" "--link" "--acceptance" "--external-ref")
  "Flags that the create transient surfaces as comma-list infixes.
At commit time, each non-empty comma-separated value yields its own
`--flag value' pair in argv.")

(defun knot-create--expand-args (transient-args)
  "Expand TRANSIENT-ARGS into a flat list of CLI argv strings.
TRANSIENT-ARGS is the return of `(transient-args 'knot-create)' —
a list of `--flag=value' strings.  Repeatable flags (see
`knot-create--repeatable-flags') split their value on commas and
emit one `--flag value' pair per non-empty entry.  Other flags emit
a single pair when the value is non-empty; empty values are skipped
so a backed-out infix doesn't reach the CLI."
  (let (out)
    (dolist (arg transient-args)
      (when (string-match "\\`\\(--[a-z-]+\\)=\\(.*\\)\\'" arg)
        (let ((flag (match-string 1 arg))
              (value (match-string 2 arg)))
          (cond
           ((member flag knot-create--repeatable-flags)
            (dolist (v (split-string value "," t "[ \t]+"))
              (unless (string-empty-p v)
                (push flag out)
                (push v out))))
           ((not (string-empty-p value))
            (push flag out)
            (push value out))))))
    (nreverse out)))

(defun knot-create--read-title ()
  "Prompt for a ticket title; signal `user-error' on empty input."
  (let ((title (read-string "title: ")))
    (when (string-empty-p title)
      (user-error "knot-create: title is required"))
    title))

(defun knot-show--goto-description ()
  "Move point to the `## Description' heading if present, else point-min.
Used by `knot-create' post-commit so the new show buffer parks point
where the user would start writing the body."
  (goto-char (point-min))
  (when (re-search-forward "^## Description\\b" nil t)
    (beginning-of-line)))

(defun knot-create--run (argv)
  "Run `knot create' with ARGV and drop into the new ticket's show buffer.
ARGV is the list of strings after the `create' verb (title first, then
any flag/value pairs).  Buffer point and window-point are parked on
the `## Description' heading (or point-min when no body is rendered).
Other visible knot.el buffers for this project are refreshed via
`knot--after-mutation' so a visible list buffer picks up the new
row alongside the popped show buffer."
  (let* ((data (knot-cli-call (cons "create" argv)))
         (id (alist-get 'id data)))
    (unless (and id (not (string-empty-p id)))
      (user-error "knot-create: CLI succeeded without returning an id"))
    (let* ((buf (knot-show--open id))
           (pt (with-current-buffer buf
                 (knot-show--goto-description)
                 (point)))
           (win (get-buffer-window buf)))
      (when win (set-window-point win pt))
      (knot--after-mutation)
      buf)))

(defun knot-create--commit (&optional args)
  "Prompt for a title and invoke `knot create' with transient ARGS."
  (interactive (list (transient-args 'knot-create)))
  (let* ((title (knot-create--read-title))
         (argv (cons title (knot-create--expand-args args))))
    (knot-create--run argv)))

;;;###autoload
(transient-define-prefix knot-create ()
  "Create a new knot ticket.

Infix args set the create-time flags; the title is prompted in the
minibuffer when you commit (`c').  Press `?' to see all bindings.
Reader completions and defaults come from the cached `knot info'
envelope, so allowed values track the project's configuration.

Repeatable flags (`-d', `-L', `-A', `-r') accept a comma-list; each
non-empty entry produces its own `--flag value' pair in argv."
  ["Options"
   ("-t" "type"               knot-create:--type)
   ("-p" "priority"           knot-create:--priority)
   ("-m" "mode"               knot-create:--mode)
   ("-T" "tags (comma)"       knot-create:--tags)
   ("-a" "assignee"           knot-create:--assignee)
   ("-P" "parent"             knot-create:--parent)
   ("-d" "deps (TAB)"         knot-create:--dep)
   ("-L" "links (TAB)"        knot-create:--link)
   ("-A" "acceptance (comma)" knot-create:--acceptance)
   ("-r" "external (comma)"   knot-create:--external-ref)]
  ["Commit"
   ("c" "create"              knot-create--commit)])

;;;###autoload
(defun knot-create-quick ()
  "Prompt for a title and create a ticket with all defaults.
Drops into the new ticket's show buffer with point on `## Description'."
  (interactive)
  (knot-create--run (list (knot-create--read-title))))


;;;; Lifecycle transitions (start / close)
;;
;; `s' (start) and `x' (close) in list or show buffers.  Context id is
;; resolved via `knot-deps--context-id' (same rule: show buffer uses its
;; ticket id; list buffer uses the row at point).  `knot close' prompts
;; for a summary; the acceptance gate's `acceptance_incomplete' envelope
;; surfaces via `knot-cli--parse' as a `user-error' carrying the
;; envelope's message field.

;;;###autoload
(defun knot-start ()
  "Transition the contextual ticket via `knot start <id>'.
Context id comes from the show buffer's `knot-show--id' or the
tabulated-list row at point.  Refresh propagates to every visible
knot.el buffer for this project via `knot--after-mutation'."
  (interactive)
  (let ((id (knot-deps--context-id)))
    (knot-cli-call (list "start" id))
    (knot--after-mutation)))

;;;###autoload
(defun knot-close ()
  "Prompt for a closing summary and close the contextual ticket.
Calls `knot close <id> --summary \"...\"' and refreshes.  When the
acceptance gate fires, the CLI envelope's message surfaces as a
`user-error' in the minibuffer (via `knot-cli-call'); buffer state is
unchanged.  Refresh propagates to every visible knot.el buffer
for this project via `knot--after-mutation'."
  (interactive)
  (let* ((id (knot-deps--context-id))
         (summary (read-string "closing summary: ")))
    (knot-cli-call (list "close" id "--summary" summary))
    (knot--after-mutation)))


;;;; Deps + links transients (knot-deps / knot-links modules)

(defun knot-deps--context-id ()
  "Return the contextual ticket id for the deps / links transients.

In `knot-show-mode' the buffer's `knot-show--id' is used; in
`knot-list-mode' the id of the tabulated row at point is used."
  (cond
   ((derived-mode-p 'knot-show-mode)
    (unless (and knot-show--id (not (string-empty-p knot-show--id)))
      (user-error "knot-deps: no ticket id in this show buffer"))
    knot-show--id)
   ((derived-mode-p 'knot-list-mode)
    (or (tabulated-list-get-id)
        (user-error "knot-deps: no ticket on this list row")))
   (t
    (user-error "knot-deps: not in a knot.el show or list buffer"))))

(defun knot-deps--refresh-origin ()
  "Refresh every visible knot.el buffer in this project after a deps mutation.
Thin wrapper around `knot--after-mutation' kept for call-site
clarity at the deps / links transient suffixes."
  (knot--after-mutation))

(defun knot-deps--format-row (id title)
  "Format an `id  title' completion candidate for ID and TITLE."
  (format "%s  %s" (or id "") (or title "")))

(defun knot-deps--extract-id (pick)
  "Return the id substring from a `id  title' candidate PICK."
  (cond
   ((or (null pick) (string-empty-p pick))
    (user-error "knot-deps: no selection"))
   ((string-match "\\`\\(\\S-+\\)" pick) (match-string 1 pick))
   (t pick)))

(defun knot-deps--live-choices (&optional exclude-id)
  "Return a list of `id  title' completion candidates for live tickets.
EXCLUDE-ID, when non-nil, is filtered out so a ticket cannot
self-reference."
  (let* ((rows (knot-cli-call '("list")))
         (rows (if exclude-id
                   (cl-remove-if (lambda (r)
                                   (equal exclude-id (alist-get 'id r)))
                                 rows)
                 rows)))
    (mapcar (lambda (r)
              (knot-deps--format-row
               (alist-get 'id r)
               (alist-get 'title r)))
            rows)))

(defun knot-deps--read-live (prompt exclude-id)
  "Prompt for a live ticket via `completing-read', defaulting to nil.

Candidates are formatted `id  title' from `knot list --json' (live
tickets only).  EXCLUDE-ID, when non-nil, is filtered out so a
ticket cannot depend on / link itself."
  (let ((choices (knot-deps--live-choices exclude-id)))
    (unless choices
      (user-error "knot-deps: no live tickets to choose from"))
    (knot-deps--extract-id
     (completing-read prompt choices nil t))))

(defun knot-deps--id->title-map ()
  "Return a hash mapping every live and closed ticket id to its title.

Used so undep / unlink candidate lists can show titles even for
relationships that point to archived tickets."
  (let ((m (make-hash-table :test 'equal)))
    (dolist (r (knot-cli-call '("list")))
      (puthash (alist-get 'id r) (alist-get 'title r) m))
    (dolist (r (knot-cli-call '("list" "--status" "closed")))
      (puthash (alist-get 'id r) (alist-get 'title r) m))
    m))

(defun knot-deps--read-current (prompt ids)
  "Prompt for one of IDS via `completing-read'.

IDS is a list of ticket id strings — typically the contextual
ticket's `deps' or `links'.  Each candidate is rendered as `id
title' with the title looked up across live + closed tickets so
archived relationships display correctly."
  (unless (and ids (listp ids) (not (null ids)))
    (user-error "knot-deps: no relationships to remove"))
  (let* ((titles (knot-deps--id->title-map))
         (choices (mapcar (lambda (id)
                            (knot-deps--format-row
                             id (gethash id titles)))
                          ids)))
    (knot-deps--extract-id
     (completing-read prompt choices nil t))))

(defun knot-deps--current-field (id field)
  "Fetch ID's FIELD list from a fresh `knot show' call."
  (alist-get field (knot-cli-call (list "show" id))))

(defun knot-deps-add ()
  "Add a dep to the contextual ticket via `knot dep <id> <to>'."
  (interactive)
  (let* ((id (knot-deps--context-id))
         (to (knot-deps--read-live
              (format "Add dep to %s: " id) id)))
    (knot-cli-call (list "dep" id to))
    (knot-deps--refresh-origin)))

(defun knot-deps-remove ()
  "Remove one of the contextual ticket's deps via `knot undep <id> <to>'."
  (interactive)
  (let* ((id (knot-deps--context-id))
         (deps (knot-deps--current-field id 'deps))
         (to (knot-deps--read-current
              (format "Remove dep from %s: " id) deps)))
    (knot-cli-call (list "undep" id to))
    (knot-deps--refresh-origin)))

(defun knot-deps-tree ()
  "Open the deps tree buffer for the contextual ticket."
  (interactive)
  (knot-deps--open (knot-deps--context-id) (current-buffer)))

;;;###autoload
(transient-define-prefix knot-deps-transient ()
  "Manage deps for the contextual ticket."
  ["Deps"
   ("a" "add"          knot-deps-add)
   ("r" "remove"       knot-deps-remove)
   ("t" "tree (open)"  knot-deps-tree)])

(defun knot-links-add ()
  "Add a symmetric link to the contextual ticket via `knot link <id> <to>'."
  (interactive)
  (let* ((id (knot-deps--context-id))
         (to (knot-deps--read-live
              (format "Add link to %s: " id) id)))
    (knot-cli-call (list "link" id to))
    (knot-deps--refresh-origin)))

(defun knot-links-remove ()
  "Remove one of the contextual ticket's links via `knot unlink <id> <to>'."
  (interactive)
  (let* ((id (knot-deps--context-id))
         (links (knot-deps--current-field id 'links))
         (to (knot-deps--read-current
              (format "Remove link from %s: " id) links)))
    (knot-cli-call (list "unlink" id to))
    (knot-deps--refresh-origin)))

;;;###autoload
(transient-define-prefix knot-links-transient ()
  "Manage symmetric links for the contextual ticket."
  ["Links"
   ("a" "add"    knot-links-add)
   ("r" "remove" knot-links-remove)])


;;;; Deps tree buffer (knot-deps module — JSON tree view)

(defvar-local knot-deps--id nil
  "Ticket id rendered in this `knot-deps-mode' buffer.")

(defvar-local knot-deps--full nil
  "Non-nil when this `knot-deps-mode' buffer is rendering with `--full'.")

(defvar-local knot-deps--back-buffer nil
  "Buffer `knot-deps-quit' should switch to when this buffer is dismissed.

Mirrors `knot-show--back-buffer': set on entry to the buffer that
invoked the deps transient's tree-open suffix.  Nil falls through
to `quit-window'.")

(defvar knot-deps-mode-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map special-mode-map)
    (define-key map (kbd "?") #'knot)
    (define-key map (kbd "g") #'knot-refresh)
    (define-key map (kbd "f") #'knot-deps-toggle-full)
    (define-key map (kbd "q") #'knot-deps-quit)
    (define-key map (kbd "n") #'forward-button)
    (define-key map (kbd "p") #'backward-button)
    (define-key map (kbd "TAB") #'forward-button)
    (define-key map (kbd "<backtab>") #'backward-button)
    map)
  "Keymap for `knot-deps-mode'.")

(define-derived-mode knot-deps-mode special-mode "Knot-Deps"
  "Major mode for the knot deps tree buffer.

Renders `knot dep tree --json' as an indented outline with a
status glyph per node (✓ for closed, ○ for live).  Each node is a
`knot-id' button; RET opens that ticket's show.  `f' toggles
between the collapsed view and `--full' (which expands duplicate
subtrees).

\\{knot-deps-mode-map}"
  (setq truncate-lines nil)
  (setq buffer-read-only t))

(defun knot-deps--buffer-name (project id)
  "Return the canonical deps buffer name for PROJECT and ID."
  (format "*knot-deps: %s · %s*" project id))

(defun knot-deps--status-glyph (status)
  "Return the status glyph for STATUS — ✓ when closed, ○ otherwise."
  (if (and status (stringp status) (string= status "closed"))
      "✓"
    "○"))

(defun knot-deps--insert-node (node depth)
  "Render NODE (a parsed `dep tree' entry) at indent DEPTH.

Inserts `  ' × DEPTH, the status glyph, the id (as a `knot-id'
button), and the title.  Recurses on each entry under `deps'.

Two CLI-side markers are surfaced:

- `missing:true' (dangling dep reference): the row uses a `?'
  glyph, the title is replaced by `(missing)' in
  `knot-deps-missing', and recursion stops.

- `seen_before:true' (collapsed view, duplicate subtree): the
  row renders normally with a trailing ` ↑' in
  `knot-deps-seen-before' to signal the subtree was already
  expanded earlier; recursion stops."
  (let* ((id          (alist-get 'id node))
         (title       (alist-get 'title node))
         (status      (alist-get 'status node))
         (missing     (alist-get 'missing node))
         (seen-before (alist-get 'seen_before node))
         (deps        (alist-get 'deps node))
         (indent      (make-string (* depth 2) ?\s))
         (glyph       (if missing "?" (knot-deps--status-glyph status)))
         (id-str      (or id "")))
    (insert indent glyph " ")
    (let ((beg (point)))
      (insert id-str)
      (when (not (string-empty-p id-str))
        (make-text-button beg (point)
                          :type 'knot-id
                          'knot-id id-str)))
    (cond
     (missing
      (insert "  " (propertize "(missing)" 'face 'knot-deps-missing)))
     ((and title (not (string-empty-p title)))
      (insert "  " title)))
    (when seen-before
      (insert " " (propertize "↑" 'face 'knot-deps-seen-before)))
    (insert "\n")
    (when (and (not missing) (not seen-before) deps (listp deps))
      (dolist (child deps)
        (knot-deps--insert-node child (1+ depth))))))

(defun knot-deps--render (data full)
  "Render DATA (the parsed `dep tree' envelope) into the current buffer.

FULL is the value of `knot-deps--full' captured for the header
line."
  (let ((inhibit-read-only t))
    (erase-buffer)
    (setq header-line-format
          (format "knot · deps · %s · f %s · RET open · g refresh · q back"
                  (or (alist-get 'id data) "?")
                  (if full "(full)" "(collapsed)")))
    (knot-deps--insert-node data 0)))

(defun knot-deps--fetch (id full)
  "Fetch the deps tree for ID, passing `--full' when FULL is non-nil."
  (let ((args (list "dep" "tree" id)))
    (when full
      (setq args (append args (list "--full"))))
    (knot-cli-call args)))

(defun knot-deps--open (id &optional back-buffer)
  "Open the deps tree buffer for ID, reusing it when one already exists.

When BACK-BUFFER is non-nil and live and not the destination
buffer itself, it is recorded as `knot-deps--back-buffer' so `q'
switches back to it."
  (let* ((info         (knot-info-current))
         (project      (knot-info--project-name info))
         (project-root (knot-info--project-root info))
         (buf-name     (knot-deps--buffer-name project id))
         (buffer       (get-buffer-create buf-name))
         (full         (and (buffer-live-p buffer)
                            (buffer-local-value 'knot-deps--full buffer)))
         (data         (knot-deps--fetch id full)))
    (with-current-buffer buffer
      (unless (derived-mode-p 'knot-deps-mode)
        (knot-deps-mode))
      (setq-local default-directory
                  (file-name-as-directory
                   (or project-root default-directory)))
      (setq knot-deps--id id)
      (setq knot-deps--full full)
      (when (and back-buffer
                 (buffer-live-p back-buffer)
                 (not (eq back-buffer buffer)))
        (setq knot-deps--back-buffer back-buffer))
      (knot-deps--render data full)
      (goto-char (point-min)))
    (pop-to-buffer-same-window buffer)
    buffer))

(defun knot-deps--refresh ()
  "Re-fetch and re-render the current deps tree buffer."
  (unless (derived-mode-p 'knot-deps-mode)
    (user-error "knot-deps--refresh: not in a knot-deps-mode buffer"))
  (let* ((id   knot-deps--id)
         (full knot-deps--full)
         (data (knot-deps--fetch id full))
         (pt   (point)))
    (knot-deps--render data full)
    (goto-char (min pt (point-max)))))

(defun knot-deps-toggle-full ()
  "Toggle between the collapsed and `--full' rendering of this deps tree."
  (interactive)
  (unless (derived-mode-p 'knot-deps-mode)
    (user-error "knot-deps-toggle-full: not in a knot-deps-mode buffer"))
  (setq knot-deps--full (not knot-deps--full))
  (knot-deps--refresh))

(defun knot-deps-quit ()
  "Back-button for the deps tree buffer.

Switches to `knot-deps--back-buffer' when set and live, falling
back to `quit-window' otherwise."
  (interactive)
  (let ((back knot-deps--back-buffer))
    (if (and back (buffer-live-p back))
        (switch-to-buffer back)
      (quit-window))))


;;;; Refresh (single-buffer `g' + cross-buffer mutation walker)

(defconst knot--buffer-modes
  '(knot-list-mode knot-info-mode knot-show-mode knot-deps-mode)
  "Major modes that count as knot.el buffers for refresh propagation.")

(defun knot--buffer-project-root (buffer)
  "Return BUFFER's project root truename when it is a knot.el buffer.
Returns nil otherwise.  Used by the cross-buffer refresh walker
to gate propagation by project."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (when (and (apply #'derived-mode-p knot--buffer-modes)
                 default-directory)
        (file-truename default-directory)))))

(defun knot--refresh-current-buffer ()
  "Re-render the current knot.el buffer in place.
Dispatch helper shared by `knot-refresh' (manual `g') and the
cross-buffer mutation walker.  Assumes the cached info envelope
is already invalid for this project; callers handle invalidation."
  (cond
   ((derived-mode-p 'knot-list-mode)
    (knot-list--render))
   ((derived-mode-p 'knot-info-mode)
    (let ((inhibit-read-only t))
      (erase-buffer)
      (knot-info--render (knot-info-current))
      (goto-char (point-min))))
   ((derived-mode-p 'knot-show-mode)
    (knot-show--refresh))
   ((derived-mode-p 'knot-deps-mode)
    (knot-deps--refresh))))

(defun knot--after-mutation (&optional project-root)
  "Invalidate the info cache and refresh visible knot.el buffers in PROJECT-ROOT.

PROJECT-ROOT defaults to the originating buffer's
`default-directory'.  A buffer is considered visible when it
appears in at least one window on any visible frame (see
`get-buffer-window-list').  Buried (live but undisplayed) buffers
are intentionally not refreshed; `g' (`knot-refresh') remains the
manual escape hatch in any knot.el buffer.

Every mutating command (`knot-update--commit', `knot-start',
`knot-close', the AC and dep / link interactions, the capture
commit, the emacsclient escape hatch, and `knot-create--run') calls
this in place of a self-only `knot-show--refresh' so a status
change in show propagates to a visible list buffer alongside it."
  (let ((root (file-truename (or project-root default-directory))))
    (knot-info-invalidate root)
    (dolist (buf (buffer-list))
      (when (and (equal (knot--buffer-project-root buf) root)
                 (get-buffer-window-list buf nil 0))
        (with-current-buffer buf
          (knot--refresh-current-buffer))))))

(defun knot-refresh ()
  "Refresh the current knot.el buffer in place.

In `knot-list-mode' the active view and filter state are
preserved and point is restored to the previous row id when
possible.  In `knot-info-mode' the cached info envelope is
invalidated and the buffer is re-rendered.  In `knot-show-mode'
the ticket is re-fetched and re-rendered.  In `knot-deps-mode'
the deps tree is re-fetched and re-rendered.

Does not propagate to other knot.el buffers for the same project;
cross-buffer refresh is reserved for mutating commands (see
`knot--after-mutation').  External edits (from another agent or
terminal session) surface only on the next manual `g' in each
buffer."
  (interactive)
  (unless (apply #'derived-mode-p knot--buffer-modes)
    (user-error "knot-refresh: not in a knot.el buffer"))
  (knot-info-invalidate default-directory)
  (knot--refresh-current-buffer))


(provide 'knot)
;;; knot.el ends here
