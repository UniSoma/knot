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
;; This file currently lands slices 1-3 of the v0.1 plan: the CLI
;; boundary, the project oracle (`knot-info-current'), the dispatch
;; transient (`M-x knot'), a single project-scoped list buffer that
;; flips in place between list / ready / blocked / closed views (`l'
;; / `r' / `b' / `c'), with a filter transient on `f' covering --mode
;; / --type / --status / --tag / --assignee / --limit /
;; --acceptance-complete and `g' as the manual refresh, and a
;; markdown-view-mode show buffer with buttonized ticket ids, AC-line
;; RET flipping done/undone, +/a / -/k for AC add/remove, ]/[ for
;; next/previous in the originating list buffer, and a multi-level
;; back-button on `q' that walks the entry chain across drill-ins.
;; Mutations, capture buffers, and the deps tree arrive in
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

(defun knot-info-current (&optional directory)
  "Return the cached info envelope for DIRECTORY (default `default-directory').
On a cache miss, runs `knot info --json' from DIRECTORY and caches
the parsed `data' field."
  (let* ((key (file-truename (or directory default-directory)))
         (hit (gethash key knot-info--cache)))
    (or hit
        (let* ((default-directory key)
               (data (knot-cli-call '("info"))))
          (puthash key data knot-info--cache)
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
  "Clear the info cache for DIRECTORY, or all entries when DIRECTORY is nil."
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
   ("b" "blocked"   knot-blocked)
   ("c" "closed"    knot-closed)]
  ["Create"
   ("n" "new"       knot-create)
   ("N" "quick new" knot-create-quick)]
  ["Other"
   ("i" "info"      knot-info-show)
   ("g" "refresh"   knot-refresh)])

;;;###autoload
(defalias 'knot-status #'knot
  "Dispatch entry alias.  Bind `C-c k' (or similar) to `knot' or `knot-status'.")


;;;; Slice-boundary stubs

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

(defun knot-create ()
  "Open the create transient.
Stub for slice 1; implemented in slice 6 (kno-01kreh5wz1mb)."
  (interactive)
  (user-error "knot-create: arrives with slice 6 create + quick-create (kno-01kreh5wz1mb)"))

(defun knot-create-quick ()
  "Run quick-create: prompt for a title and create with all defaults.
Stub for slice 1; implemented in slice 6 (kno-01kreh5wz1mb)."
  (interactive)
  (user-error "knot-create-quick: arrives with slice 6 create + quick-create (kno-01kreh5wz1mb)"))


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
    (define-key map (kbd "k") #'knot-show-remove-ac)
    (define-key map (kbd "]") #'knot-show-next-ticket)
    (define-key map (kbd "[") #'knot-show-prev-ticket)
    (define-key map (kbd "n") #'forward-button)
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

(defun knot-show-RET ()
  "Dispatch RET in a show buffer.

A knot-id button at point wins (drills into that ticket).
Otherwise an acceptance-criterion line at point wins (flips its
done state).  Any other button at point is pushed last.  Errors
when there is nothing actionable."
  (interactive)
  (let ((btn (button-at (point))))
    (cond
     ((and btn (button-get btn 'knot-id))
      (push-button (point)))
     ((knot-show--ac-at-point)
      (knot-show-flip-ac))
     (btn
      (push-button (point)))
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
      (knot-show--refresh))))

(defun knot-show-add-ac ()
  "Prompt for a new acceptance criterion and add it via `--add-ac'."
  (interactive)
  (unless knot-show--id
    (user-error "knot-show: no ticket in this buffer"))
  (let ((title (read-string "New acceptance criterion: ")))
    (when (or (null title) (string-empty-p (string-trim title)))
      (user-error "knot-show: empty criterion title"))
    (knot-cli-call (list "update" knot-show--id "--add-ac" title))
    (knot-show--refresh)))

(defun knot-show-remove-ac ()
  "Remove the acceptance criterion at point via `--remove-ac' after confirmation."
  (interactive)
  (let ((title (knot-show--ac-at-point)))
    (unless title
      (user-error "knot-show: not on an acceptance criterion line"))
    (when (yes-or-no-p (format "Remove acceptance criterion %S? " title))
      (knot-cli-call (list "update" knot-show--id "--remove-ac" title))
      (knot-show--refresh))))

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

(defun knot-show--render-relationship (label entries)
  "Render a markdown section LABEL listing relationship ENTRIES."
  (when (and entries (listp entries) (not (null entries)))
    (insert (format "## %s\n\n" label))
    (dolist (entry entries)
      (let ((id     (alist-get 'id entry))
            (title  (alist-get 'title entry))
            (status (alist-get 'status entry)))
        (insert (format "- %s [%s] %s\n"
                        (or id "")
                        (or status "?")
                        (or title "")))))
    (insert "\n")))

(defun knot-show--render-scalar-list (label items)
  "Insert a `**LABEL:** csv' line for ITEMS when ITEMS is a non-empty list."
  (when (and items (listp items) (not (null items)))
    (insert (format "**%s:** %s\n" label (string-join items ", ")))))

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
      (insert (format "**status:** %s · **type:** %s · **priority:** %s · **mode:** %s\n"
                      (or status "-")
                      (or type "-")
                      (if (numberp priority) (number-to-string priority) "-")
                      (or mode "-")))
      (when (and assignee (not (string-empty-p assignee)))
        (insert (format "**assignee:** %s\n" assignee)))
      (when (and parent (stringp parent) (not (string-empty-p parent)))
        (insert (format "**parent:** %s\n" parent)))
      (knot-show--render-scalar-list "tags" tags)
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
      (knot-show--render-relationship "Blockers" blockers)
      (knot-show--render-relationship "Blocking" blocking)
      (knot-show--render-relationship "Children" children)
      (knot-show--render-relationship "Linked" linked))
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


;;;; Refresh (single-buffer; cross-buffer walk lands in slice 8)

(defun knot-refresh ()
  "Refresh the current knot.el buffer in place.

In `knot-list-mode' the active view and filter state are
preserved and point is restored to the previous row id when
possible.  In `knot-info-mode' the cached info envelope is
invalidated and the buffer is re-rendered.  In `knot-show-mode'
the ticket is re-fetched and re-rendered."
  (interactive)
  (knot-info-invalidate default-directory)
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
   (t
    (user-error "knot-refresh: not in a knot.el buffer"))))


(provide 'knot)
;;; knot.el ends here
