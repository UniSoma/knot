(ns knot.acceptance
  "Acceptance criteria are stored as a vector of `{:title :done}` entries
   in frontmatter. The `## Acceptance Criteria` body section is never
   stored on disk — `knot show` synthesizes it from frontmatter at
   display time. This namespace owns the pure transformations: render
   to markdown, resolve an ordinal or title to an entry, validate the
   on-disk shape, parse
   a body section (one-shot migration only)."
  (:require [clojure.string :as str]))

(defn progress
  "Return `[done-count total-count]` for `acceptance`. `[0 0]` on
   empty/nil so callers can render \"0/0\" without nil-checks."
  [acceptance]
  (let [total (count acceptance)
        done  (count (filter :done acceptance))]
    [done total]))

(defn open-titles
  "Return a vector of `:title` values from `acceptance` whose `:done`
   is not truthy, preserving the original order. Empty vector on
   empty/nil."
  [acceptance]
  (vec (keep (fn [{:keys [title done]}]
               (when-not done title))
             acceptance)))

(defn complete?
  "True when every entry in `acceptance` has `:done true`. Vacuously
   true on empty/nil — callers that need to gate on \"some AC and not
   complete\" must combine with `(seq acceptance)`."
  [acceptance]
  (every? :done acceptance))

(def provenance-comment
  "The line `render-section` prints under the heading. The section is
   derived, not stored: an HTML comment says so where an agent reads the
   render, and stays invisible where a human views the markdown."
  "<!-- from frontmatter acceptance; edit with --add-ac / --ac -->")

(defn render-section
  "Format an `:acceptance` vector as a `## Acceptance Criteria` markdown
   block, preceded by a leading blank-line separator and carrying
   `provenance-comment` under the heading. Each entry is
   numbered with its 1-based ordinal — the number `--ac` and
   `--remove-ac` accept in place of the title. Returns `\"\"`
   when the vector is nil/empty so callers can concatenate
   unconditionally."
  [acceptance]
  (if (empty? acceptance)
    ""
    (str "\n## Acceptance Criteria\n" provenance-comment "\n\n"
         (str/join "\n"
                   (map-indexed (fn [i {:keys [title done]}]
                                  (str (inc i) ". [" (if done "x" " ") "] " title))
                                acceptance))
         "\n")))

(defn from-titles
  "Lift a seq of title strings into the structured shape stored in
   frontmatter: `[{:title <s> :done false} ...]`. Blank/nil titles are
   dropped. Returns nil when no usable titles remain so callers can
   omit the YAML key entirely."
  [titles]
  (let [entries (->> titles
                     (map (fn [t] (some-> t str/trim not-empty)))
                     (remove nil?)
                     (mapv (fn [t] {:title t :done false})))]
    (when (seq entries) entries)))

(defn ordinal?
  "True when `arg` addresses a criterion by position rather than by
   title. All digits is an ordinal; anything else is a title. The single
   home of that rule — every caller that discriminates the two readings
   asks here."
  [arg]
  (boolean (re-matches #"\d+" (str arg))))

(defn resolve-index
  "Resolve `arg` against `acceptance` to a 0-based index. An all-digits
   `arg` is a 1-based ordinal into the list; anything else is an exact,
   case-sensitive title match (first match wins). Returns nil when
   nothing matches — an out-of-range ordinal, or an unknown title.

   The ordinal reading wins outright: an entry whose title is all
   digits is not reachable by that title, only by its own ordinal.
   Rename it to address it by name."
  [acceptance arg]
  (let [vec* (vec (or acceptance []))]
    (if (ordinal? arg)
      (let [i (dec (parse-long arg))]
        (when (and (nat-int? i) (< i (count vec*))) i))
      (->> (map-indexed vector vec*)
           (some (fn [[i e]] (when (= arg (:title e)) i)))))))

(def ^:private section-heading "## Acceptance Criteria")

(defn- section-region
  "Locate `## Acceptance Criteria` in `body`. Returns `{:start :end}`
   where `:end` is the start of the next `## ` heading or
   `(count body)`. Returns nil when the heading is missing."
  [body]
  (let [body* (or body "")
        pat   (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote section-heading)
                               "[ \\t]*$"))
        m     (re-matcher pat body*)]
    (when (.find m)
      (let [next-m (re-matcher #"(?m)^## " body*)
            after  (.end m)
            end    (if (.find next-m after)
                     (.start next-m)
                     (count body*))]
        {:start (.start m) :end end}))))

(def ^:private bullet-line-pat
  ;; `- [<x|space>] title` (checkbox form) OR `- title` (plain bullet).
  ;; Group 1 captures the optional checkbox marker; group 2 is the title.
  ;; Uppercase X is accepted. Plain bullets are treated as undone.
  #"(?m)^[ \t]*-[ \t]+(?:\[([ xX])\][ \t]+)?(.+?)[ \t]*$")

(defn parse-body-section
  "Parse the `## Acceptance Criteria` section out of `body`. Each
   top-level `- ...` bullet becomes `{:title <s> :done <bool>}`. The
   checkbox forms `- [ ] / - [x] / - [X]` set `:done` from the marker;
   a plain `- title` bullet (no checkbox) defaults to `:done false`.
   Non-bullet prose inside the section is silently ignored. Returns
   nil when the section is absent or contains no bullets — migration
   callers treat that as a no-op."
  [body]
  (when-let [{:keys [start end]} (section-region body)]
    (let [section (subs body start end)
          entries (vec (for [[_ marker title] (re-seq bullet-line-pat section)]
                         {:title (str/trim title)
                          :done  (and (some? marker) (not= marker " "))}))]
      (when (seq entries) entries))))

(defn strip-body-section
  "Return `body` with any `## Acceptance Criteria` section removed.
   No-op when the section is absent. Idempotent: stripping a stripped
   body is the same as stripping it once."
  [body]
  (let [body* (or body "")]
    (if-let [{:keys [start end]} (section-region body*)]
      (let [head (str/replace (subs body* 0 start) #"\s+$" "")
            tail (subs body* end)]
        (cond
          (str/blank? tail) (if (seq head) (str head "\n") "")
          (seq head)        (str head "\n\n" tail)
          :else             tail))
      body*)))

(defn migrate-ticket
  "Lift the body's `## Acceptance Criteria` section into a frontmatter
   `:acceptance` list and strip the section from the body. Returns the
   input unchanged when the body has no AC section to lift — already-
   migrated tickets pass through untouched (idempotent)."
  [{:keys [frontmatter body] :as ticket}]
  (if-let [parsed (parse-body-section body)]
    (-> ticket
        (assoc :frontmatter (assoc frontmatter :acceptance parsed))
        (assoc :body (strip-body-section body)))
    ticket))
