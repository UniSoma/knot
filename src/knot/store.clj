(ns knot.store
  "Filesystem boundary: load-all/load-one/save!. Centralizes
   :updated/:closed timestamping, archive auto-move, symmetric link
   maintenance."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [knot.ticket :as ticket]))

(defn ticket-path
  "Compute the live-directory path of a ticket from its id and slug.
   Returns `<project-root>/<tickets-dir>/<id>--<slug>.md`, or
   `<project-root>/<tickets-dir>/<id>.md` when the slug is blank."
  [project-root tickets-dir id slug]
  (let [filename (if (str/blank? slug)
                   (str id ".md")
                   (str id "--" slug ".md"))]
    (str (fs/path project-root tickets-dir filename))))

(defn save!
  "Render `ticket` and write it to `<project-root>/<tickets-dir>/<id>[--<slug>].md`.
   Creates the tickets directory if missing. Returns the written path."
  [project-root tickets-dir id slug ticket]
  (let [path (ticket-path project-root tickets-dir id slug)]
    (fs/create-dirs (fs/parent path))
    (spit path (ticket/render ticket))
    path))

(defn- find-by-id-glob
  "Return the path of the file matching `<id>.md` or `<id>--*.md` under `dir`,
   or nil when none."
  [dir id]
  (let [bare    (fs/path dir (str id ".md"))
        slugged (seq (fs/glob dir (str id "--*.md")))]
    (cond
      (fs/exists? bare) (str bare)
      slugged           (str (first slugged)))))

(defn load-one
  "Load and parse the ticket whose filename starts with `<id>` from the live
   tickets directory. Returns the parsed `{:frontmatter ... :body ...}` map,
   or nil when no matching file exists."
  [project-root tickets-dir id]
  (let [dir (fs/path project-root tickets-dir)]
    (when (fs/directory? dir)
      (when-let [path (find-by-id-glob dir id)]
        (ticket/parse (slurp path))))))

(defn load-all
  "Load and parse every ticket file in the live tickets directory. Returns a
   sequence of `{:frontmatter ... :body ...}` maps, sorted by filename for
   stable order. Returns an empty seq when the tickets directory is missing
   or empty."
  [project-root tickets-dir]
  (let [dir (fs/path project-root tickets-dir)]
    (if (fs/directory? dir)
      (->> (fs/glob dir "*.md")
           (sort-by str)
           (map (comp ticket/parse slurp str)))
      ())))
