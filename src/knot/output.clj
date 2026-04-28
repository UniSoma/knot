(ns knot.output
  "Renderers: human (markdown/text + ANSI), JSON, dep-tree.
   stdout = data; stderr = warnings/errors."
  (:require [knot.ticket :as ticket]))

(defn show-text
  "Render a ticket map for the `show` command. Returns a string containing the
   YAML frontmatter and the markdown body. Computed inverse sections are
   deferred to a later slice."
  [ticket]
  (ticket/render ticket))
