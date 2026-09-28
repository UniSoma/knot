(ns knot.doc
  "Pure module for documents attached to tickets: frontmatter shape,
   identifier generation and filename derivation. A document's storage
   envelope is the ticket's — markdown with YAML frontmatter — so parse and
   render are reused from `knot.ticket` rather than reimplemented. No I/O."
  (:require [clojure.string :as str]
            [knot.ticket :as ticket]))

(def ^:private doc-marker
  "The segment distinguishing a document id from a ticket id. It follows the
   owning ticket id, so `kp-01m2s4ecygyc-d7f3k` names both the corpus and the
   owner: an agent reading the id knows which ticket owns the document without
   a lookup, and cannot mistake it for the ticket id it embeds.

   The marker matters because the owner alone is not a document key — a ticket
   owns several — so it is the marker plus the random tail that makes the
   filename's leading segment unique, which is what the store's straggler
   sweep depends on."
  "d")

(def ^:private suffix-chars
  "Random chars after the marker. Four is ample for the few documents one
   ticket owns, and `check` reports a collision rather than the generator
   preventing one."
  4)

(defn generate-id
  "Generate a document id: `<owning-ticket-id>-d<4 random Crockford base32
   chars>`, e.g. `kp-01m2s4ecygyc-d7f3k`.

   The owning ticket leads rather than the bare prefix, so the id carries its
   owner. That is what keeps a document id from reading as a near-miss of a
   ticket id — the two differ by a whole trailing segment now, not by one
   letter in the middle.

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
  ;; `<prefix>-<ticket-suffix>-d<suffix>--<slug>.md`. Two hyphen-separated
  ;; segments before the `-d` marker is what distinguishes a document filename
  ;; from a ticket one: a ticket has only `<prefix>-<suffix>`, so it never
  ;; matches.
  #"^([a-z0-9]+-[0-9a-z]+-d[0-9a-z]+)--.*\.md$")

(defn owner-of
  "The owning ticket id embedded in document id `did`, or nil when `did` does
   not have the document shape.

   The `:ticket` frontmatter field stays authoritative — ADR-0016 R10 says the
   field decides and everything else locates — so this exists for `check` to
   compare the two. Nesting the owner in the id creates a second place the
   ownership is written down, and two places that can disagree need a check
   that says so rather than a rule about which one wins."
  [did]
  (when (string? did)
    (second (re-matches #"^([a-z0-9]+-[0-9a-z]+)-d[0-9a-z]+$" did))))

(defn id-of
  "The leading document-id segment of `fname`, or nil when it does not name a
   document. A ticket filename returns nil rather than a truncated id."
  [fname]
  (when (string? fname)
    (second (re-matches filename-pat fname))))
