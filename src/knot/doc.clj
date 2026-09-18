(ns knot.doc
  "Pure module for documents attached to tickets: frontmatter shape,
   identifier generation and filename derivation. A document's storage
   envelope is the ticket's — markdown with YAML frontmatter — so parse and
   render are reused from `knot.ticket` rather than reimplemented. No I/O."
  (:require [clojure.string :as str]
            [knot.ticket :as ticket]))

(def ^:private doc-marker
  "The segment distinguishing a document id from a ticket id. It buys two
   things: a human reading `kno-d01…` can tell which corpus the id addresses,
   and a mistyped command fails loudly instead of resolving in the wrong one.
   It sits after the prefix rather than leading the filename, because the
   store's straggler sweep is correct only while the leading globbed segment
   uniquely identifies one record's files."
  "d")

(defn generate-id
  "Generate a document id: `<prefix>-d<12 Crockford base32 chars>`. Delegates
   to the ticket factory for the monotonic suffix, then marks it.

   The split is unconditionally safe: `config/validate!` enforces `:prefix`
   against `[a-z0-9]+` and `ticket/derive-prefix` only ever emits that, so a
   prefix can never contain a hyphen. A document id can never collide with a
   ticket id either — both suffixes are fixed width, so a ticket is always
   prefix plus 12 characters and a document always prefix plus 13."
  [prefix]
  (let [tid (ticket/generate-id prefix)
        [p suffix] (str/split tid #"-" 2)]
    (str p "-" doc-marker suffix)))

(def required-fields
  "Frontmatter keys every stored document carries."
  [:id :ticket :title :type :created :updated])

(defn valid?
  "True when `doc`'s frontmatter carries every required field as a non-blank
   string."
  [doc]
  (let [fm (:frontmatter doc)]
    (every? (fn [k]
              (let [v (get fm k)]
                (and (string? v) (not (str/blank? v)))))
            required-fields)))

(defn filename
  "`<document-id>--<slug>.md`. The document's OWN id leads, never the owning
   ticket: an owner is not unique across a ticket's documents, and a
   non-unique key in that position makes the store's sweep operate on a set
   that is several records' files rather than one."
  [id title]
  (str id "--" (ticket/derive-slug title) ".md"))

(def ^:private filename-pat
  ;; `<prefix>-d<suffix>--<slug>.md`. The `-d` is what distinguishes a
  ;; document filename from a ticket one, so a ticket file never matches.
  #"^([a-z0-9]+-d[0-9a-z]+)--.*\.md$")

(defn id-of
  "The leading document-id segment of `fname`, or nil when it does not name a
   document. A ticket filename returns nil rather than a truncated id."
  [fname]
  (when (string? fname)
    (second (re-matches filename-pat fname))))
