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
;; This file currently lands slice 1 of the v0.1 plan: the CLI
;; boundary, the project oracle (`knot-info-current'), the dispatch
;; transient (`M-x knot'), and the default list view (`knot-list').
;; View switching, the show buffer, mutations, capture buffers, and
;; the deps tree arrive in subsequent slices.
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
;; markdown-mode is declared in Package-Requires because slice 3's
;; show buffer derives from `markdown-view-mode'.  It is loaded
;; lazily by the show buffer when that slice lands.
(declare-function markdown-view-mode "markdown-mode")


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


;;;; Id display (knot-id module — display half; buttonize lands in slice 3)

(defun knot-id-format (id &optional title)
  "Return a display string for ID, optionally followed by TITLE.
The id substring is propertized with `knot-id'."
  (let ((label (propertize (or id "") 'face 'knot-id)))
    (if (and title (not (string-empty-p title)))
        (format "%s  %s" label title)
      label)))


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

(defun knot-ready ()
  "Open the ready view.
Stub for slice 1; implemented in slice 2 (kno-01kreh3g266x)."
  (interactive)
  (user-error "knot-ready: arrives with slice 2 view switching (kno-01kreh3g266x)"))

(defun knot-blocked ()
  "Open the blocked view.
Stub for slice 1; implemented in slice 2 (kno-01kreh3g266x)."
  (interactive)
  (user-error "knot-blocked: arrives with slice 2 view switching (kno-01kreh3g266x)"))

(defun knot-closed ()
  "Open the closed view.
Stub for slice 1; implemented in slice 2 (kno-01kreh3g266x)."
  (interactive)
  (user-error "knot-closed: arrives with slice 2 view switching (kno-01kreh3g266x)"))

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


;;;; List view (knot-list module — default view only)

(defvar knot-list-mode-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map tabulated-list-mode-map)
    (define-key map (kbd "?") #'knot)
    map)
  "Keymap for `knot-list-mode'.")

(define-derived-mode knot-list-mode tabulated-list-mode "Knot-List"
  "Major mode for the knot list buffer.

Renders the live ticket list as a sortable tabulated list.  The
columns mirror the terminal `knot list' output: id, status,
priority, mode, type, assignee, acceptance progress, and title.

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
  (tabulated-list-init-header))

;;;###autoload
(defun knot-list ()
  "Open the default knot list buffer for the current project.
Reuses `*knot-list: <project>*' when it already exists."
  (interactive)
  (let* ((info (knot-info-current))
         (project (knot-info--project-name info))
         (project-root (knot-info--project-root info))
         (rows (knot-cli-call '("list")))
         (buffer (get-buffer-create (format "*knot-list: %s*" project))))
    (with-current-buffer buffer
      (knot-list-mode)
      (setq-local default-directory
                  (file-name-as-directory (or project-root default-directory)))
      (setq tabulated-list-entries (mapcar #'knot-list--row rows))
      (tabulated-list-print t))
    (pop-to-buffer-same-window buffer)))

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


;;;; Refresh (single-buffer; cross-buffer walk lands in slice 8)

(defun knot-refresh ()
  "Refresh the current knot.el buffer in place.
Slice 1 supports `knot-list-mode' and `knot-info-mode'."
  (interactive)
  (knot-info-invalidate default-directory)
  (cond
   ((derived-mode-p 'knot-list-mode)
    (let ((rows (knot-cli-call '("list"))))
      (setq tabulated-list-entries (mapcar #'knot-list--row rows))
      (tabulated-list-print t)))
   ((derived-mode-p 'knot-info-mode)
    (let ((inhibit-read-only t))
      (erase-buffer)
      (knot-info--render (knot-info-current))
      (goto-char (point-min))))
   (t
    (user-error "knot-refresh: not in a knot.el buffer"))))


(provide 'knot)
;;; knot.el ends here
