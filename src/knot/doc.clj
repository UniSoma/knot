(ns knot.doc
  "Pure module for documents attached to tickets: frontmatter shape,
   identifier generation and filename derivation. A document's storage
   envelope is the ticket's — markdown with YAML frontmatter — so parse and
   render are reused from `knot.ticket` rather than reimplemented. No I/O."
  (:require [clojure.string :as str]
            [knot.ticket :as ticket]))

(def ^:private doc-marker
  "The segment distinguishing a document id from a ticket id. It follows the
   owning ticket id, as in `kp-01m2s4ecygyc-d7f3k`. The marker plus the random
   tail keeps the filename's leading segment unique, which the store's
   straggler sweep depends on."
  "d")

(def ^:private suffix-chars
  "Random chars after the marker. Four is ample for the few documents one
   ticket owns, and `check` reports a collision rather than the generator
   preventing one."
  4)

(defn generate-id
  "Generate a document id: `<owning-ticket-id>-d<4 random Crockford base32
   chars>`, e.g. `kp-01m2s4ecygyc-d7f3k`.

   The suffix is random, not a counter: see `ticket/random-suffix` for why."
  [ticket-id]
  (str ticket-id "-" doc-marker (ticket/random-suffix suffix-chars)))

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
  ;; `<prefix>-<ticket-suffix>-d<suffix>--<slug>.md`; a ticket filename lacks the `-d` segment.
  #"^([a-z0-9]+-[0-9a-z]+-d[0-9a-z]+)--.*\.md$")

(defn owner-of
  "The owning ticket id embedded in document id `did`, or nil when `did` does
   not have the document shape.
   The `:ticket` field stays authoritative; `check` uses this
   to report when the two disagree."
  [did]
  (when (string? did)
    (second (re-matches #"^([a-z0-9]+-[0-9a-z]+)-d[0-9a-z]+$" did))))

(defn wrong-corpus-hint
  "The clause a ticket resolver appends when `id` is document-shaped:
   \" — that is a document id; use `knot document show <id>`\". Nil otherwise,
   so a caller can `str` it unconditionally.
   Shared by `knot.main`'s message builder and `knot.store`'s not-found throw."
  [id]
  (when (owner-of id)
    (str " — that is a document id; use `knot document show " id "`")))

(defn id-of
  "The leading document-id segment of `fname`, or nil when it does not name a
   document. A ticket filename returns nil rather than a truncated id."
  [fname]
  (when (string? fname)
    (second (re-matches filename-pat fname))))
