(ns knot.query
  "Pure graph algorithms over ticket sequences:
   cycle detection, dep-tree, ready/blocked, filters.")

(defn non-terminal
  "Return only those tickets whose `:status` is not in `terminal-statuses`.
   Preserves input order. Tickets without a `:status` are kept (lenient)."
  [tickets terminal-statuses]
  (remove (fn [t]
            (contains? terminal-statuses
                       (get-in t [:frontmatter :status])))
          tickets))
