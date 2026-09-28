;;; knot-test.el --- ERT tests for knot.el -*- lexical-binding: t; -*-

;; Copyright (c) 2026 UniSoma
;; SPDX-License-Identifier: MIT

;;; Commentary:

;; Run with `bb test:elisp'.  Not part of the distributable package.

;;; Code:

(require 'ert)
(require 'knot)

(defmacro knot-test--with-fake-knot (script &rest body)
  "Run BODY with `knot-executable' bound to a shell SCRIPT."
  (declare (indent 1))
  `(let ((file (make-temp-file "fake-knot" nil ".sh")))
     (unwind-protect
         (progn
           (with-temp-file file
             (insert "#!/bin/sh\n" ,script))
           (set-file-modes file #o755)
           (let ((knot-executable file))
             ,@body))
       (delete-file file))))

(ert-deftest knot-test-stderr-warning-does-not-corrupt-envelope ()
  (knot-test--with-fake-knot
      "echo 'knot: ignoring unknown .knot.edn keys: bogus' >&2
echo '{\"schema_version\":1,\"ok\":true,\"data\":[1,2]}'\n"
    (should (equal '(1 2) (knot-cli-call '("ready"))))
    (should (equal '(1 2) (knot-cli-call '("ready") "stdin")))))

(ert-deftest knot-test-parse-failure-reports-stderr ()
  (knot-test--with-fake-knot
      "echo 'boom' >&2
echo 'not json'\n"
    (let ((err (should-error (knot-cli-call '("ready"))
                             :type 'user-error)))
      (should (string-match-p "stderr: boom" (cadr err))))))

(provide 'knot-test)
;;; knot-test.el ends here
