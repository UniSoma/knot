;;; knot-lint.el --- Batch lint helpers for knot.el -*- lexical-binding: t; -*-

;; Copyright (c) 2026 UniSoma
;; SPDX-License-Identifier: MIT

;;; Commentary:

;; Helpers loaded by the `bb lint:elisp' task.  Not part of the
;; distributable package; lives alongside `knot.el' so the lint
;; tooling moves in lockstep with the source.
;;
;; Provides two entry points:
;;
;;   `knot-lint-batch-byte-compile' -- byte-compile every file in
;;     `command-line-args-left' with warnings promoted to errors.
;;
;;   `knot-lint-batch-package-lint' -- run `package-lint-buffer' on
;;     every file in `command-line-args-left'.  Stale-but-harmless
;;     diagnostics from package-lint 0.16 (notably the Emacs >= 28
;;     "uninstallable in all released Emacs versions" warning) are
;;     printed but do not cause a non-zero exit.

;;; Code:

(require 'cl-lib)

(defvar knot-lint--known-stale-patterns
  '("uninstallable in all released Emacs versions")
  "Regexp fragments matching package-lint diagnostics we tolerate.
package-lint 0.16 predates the Emacs 28 release and incorrectly
flags `(emacs \"28.1\")' as uninstallable.  Such messages are
still printed but do not fail the lint.")

(defun knot-lint--stale-p (msg)
  "Return non-nil when MSG matches a known-stale package-lint diagnostic."
  (cl-some (lambda (pat) (string-match-p pat msg))
           knot-lint--known-stale-patterns))

(defvar byte-compile-error-on-warn)

(defun knot-lint-batch-byte-compile ()
  "Byte-compile each FILE in `command-line-args-left' (errors on warnings)."
  (unless noninteractive
    (error "`knot-lint-batch-byte-compile' is to be used only with -batch"))
  (setq byte-compile-error-on-warn t)
  (let ((had-failure nil))
    (dolist (file command-line-args-left)
      (unless (byte-compile-file (expand-file-name file))
        (setq had-failure t)))
    (kill-emacs (if had-failure 1 0))))

(defun knot-lint-batch-package-lint ()
  "Run `package-lint-buffer' on each FILE in `command-line-args-left'.
Prints every diagnostic; exits non-zero on any error or non-stale warning."
  (unless noninteractive
    (error "`knot-lint-batch-package-lint' is to be used only with -batch"))
  (require 'package-lint)
  (let ((package-lint-batch-fail-on-warnings nil)
        (text-quoting-style 'grave)
        (had-fatal nil)
        (sys-elpa "/usr/local/share/emacs/site-lisp/elpa"))
    ;; `emacs -Q' bypasses site-start.el and leaves `package-user-dir' at
    ;; its default (~/.emacs.d/elpa), so `package-archive-contents' is
    ;; empty and package-lint's installability check fails for deps that
    ;; were installed system-wide by the Dockerfile.  Point at the system
    ;; elpa dir when present so the archive cache is picked up.
    (when (file-directory-p sys-elpa)
      (setq package-user-dir sys-elpa))
    (package-initialize)
    (dolist (file command-line-args-left)
      (let* ((file (expand-file-name file))
             (base (file-name-nondirectory file)))
        (with-temp-buffer
          (insert-file-contents file t)
          (emacs-lisp-mode)
          (pcase-dolist (`(,line ,col ,type ,msg) (package-lint-buffer))
            (let ((stale (and (eq type 'warning) (knot-lint--stale-p msg))))
              (message "%s:%d:%d: %s%s: %s"
                       base line col
                       (if stale "stale-" "")
                       type msg)
              (unless stale
                (setq had-fatal t)))))))
    (kill-emacs (if had-fatal 1 0))))

(provide 'knot-lint)
;;; knot-lint.el ends here
