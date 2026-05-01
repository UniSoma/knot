(ns knot.ticket
  "Pure module: frontmatter parse↔render, ID generation, slug derivation,
   prefix derivation, schema validation. No I/O."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(def ^:private crockford-alphabet
  "Crockford base32, lowercase. I L O U are excluded."
  "0123456789abcdefghjkmnpqrstvwxyz")

(def ^:private timestamp-chars 10)
(def ^:private random-chars 2)
(def ^:private id-suffix-chars (+ timestamp-chars random-chars))

(defn- encode-base32
  "Encode `n` as `width` lowercase Crockford-base32 chars, MSB first."
  [n width]
  (loop [remaining n
         left      width
         acc       ()]
    (if (zero? left)
      (apply str acc)
      (recur (bit-shift-right remaining 5)
             (dec left)
             (cons (.charAt crockford-alphabet (bit-and remaining 0x1f)) acc)))))

(defn- encode-timestamp
  "Encode a millisecond timestamp as a 10-char Crockford-base32 string."
  [millis]
  (encode-base32 millis timestamp-chars))

(def ^:private random-space
  "Number of distinct values representable in `random-chars` (2) chars of
   Crockford base32 — 32^2 = 1024."
  (long (Math/pow 32 random-chars)))

(defn- format-id
  "Format `[ts rand]` as `<prefix>-<10ts><2rand>` in lowercase Crockford."
  [prefix ts rand-val]
  (str prefix "-" (encode-timestamp ts) (encode-base32 rand-val random-chars)))

(defn- advance-id-state
  "Pure ULID-monotonic state transition. Given the prior `state`
   (`{:ts <ms> :rand <0..1023>}` or nil), the current `now-ms`, an
   externally-supplied `fresh-rand` in `[0, 1024)`, and a `prefix`,
   return `[new-state id]`.

   Rules (matches the standard ULID monotonic spec):
     - nil state, or `now-ms > last.ts`: emit `(now-ms, fresh-rand)`.
     - `now-ms ≤ last.ts` (same-ms or backward clock skew): reuse
       `last.ts`, emit `last.rand + 1`. Ignores `fresh-rand`.
     - On rand-space overflow (`last.rand = 1023`), bump ts by 1 and
       reset rand to 0.

   Pure: takes randomness as input; no clock or atom access."
  [state now-ms fresh-rand prefix]
  (let [{:keys [ts rand]} state
        [ts* rand*] (cond
                      (nil? state)     [now-ms fresh-rand]
                      (> now-ms ts)    [now-ms fresh-rand]
                      (< (inc rand) random-space) [ts (inc rand)]
                      :else            [(inc ts) 0])]
    [{:ts ts* :rand rand*} (format-id prefix ts* rand*)]))

(defonce ^:private id-state
  ;; Holds the last `(ts, rand)` emitted by `generate-id`. `defonce` keeps
  ;; state stable across REPL reloads so the monotonic invariant survives
  ;; recompilation.
  (atom nil))

(defn generate-id
  "Generate a new ticket id of the form `<prefix>-<10 timestamp + 2 random>`.
   Within a single process the factory is monotonic (ULID spec): same-ms
   bursts are deterministically collision-free by incrementing the random
   tail rather than redrawing it."
  [prefix]
  (let [now   (System/currentTimeMillis)
        fresh (rand-int random-space)
        ;; advance-id-state is pure, so running it inside swap! is safe
        ;; even when CAS retries.
        {:keys [ts rand]} (swap! id-state
                                 (fn [s]
                                   (first (advance-id-state s now fresh prefix))))]
    (format-id prefix ts rand)))

(def ^:private fence "---")
(def ^:private fence-open "---\n")
(def ^:private fence-close "\n---\n")

(defn parse
  "Parse the contents of a ticket file into `{:frontmatter <map> :body <string>}`.
   The file is expected to start with `---\\n`, contain YAML frontmatter, then a
   line `---`, then the markdown body. If the file does not start with `---`,
   the entire content is treated as body. The frontmatter map preserves the
   key order from the source YAML."
  [s]
  (if-not (str/starts-with? s fence-open)
    {:frontmatter {} :body s}
    (let [after-open (subs s (count fence-open))
          close-idx  (str/index-of after-open fence-close)]
      (if (nil? close-idx)
        {:frontmatter {} :body s}
        (let [yaml-text (subs after-open 0 close-idx)
              body      (subs after-open (+ close-idx (count fence-close)))
              body      (cond-> body
                          (str/starts-with? body "\n") (subs 1))
              fm        (or (yaml/parse-string yaml-text) {})]
          {:frontmatter fm
           :body        body})))))

(defn render
  "Render a `{:frontmatter <map> :body <string>}` ticket back to file contents.
   Frontmatter is YAML; body is appended verbatim after the closing `---`."
  [{:keys [frontmatter body]}]
  (let [yaml-text (yaml/generate-string frontmatter
                                        :dumper-options {:flow-style :block})]
    (str fence "\n" yaml-text fence "\n\n" body)))

(def ^:private slug-max-len 50)

(defn derive-slug
  "Derive a filename slug from a ticket title.
   Steps: lowercase → strip non-ASCII (no transliteration) →
   non-alphanumerics → single hyphen → collapse hyphen runs →
   trim edges → truncate to ≤50 chars at the last hyphen-boundary.
   Returns \"\" on empty or all-stripped input."
  [title]
  (if (or (nil? title) (str/blank? title))
    ""
    (let [ascii   (str/replace (str/lower-case title) #"[^\x00-\x7f]+" "")
          hyphen  (str/replace ascii #"[^a-z0-9]+" "-")
          trimmed (-> hyphen
                      (str/replace #"^-+" "")
                      (str/replace #"-+$" ""))]
      (if (<= (count trimmed) slug-max-len)
        trimmed
        (let [head (subs trimmed 0 slug-max-len)
              cut  (str/last-index-of head \-)]
          (if cut
            (subs head 0 cut)
            head))))))

(def ^:private notes-heading "## Notes")

(defn- note-block
  "Format a note as a `**<iso>**\\n\\n<content>` block (no trailing newline)."
  [iso content]
  (str "**" iso "**\n\n" content))

(defn- notes-region
  "Locate the `## Notes` section in `body`. Returns
   `{:start <heading-start> :end <section-end>}` or nil when no heading
   matches. `:end` is the start of the next `## ` heading after Notes,
   or `(count body)` when Notes is the last section. The heading regex
   is line-anchored and requires only horizontal whitespace after
   `Notes` so it never false-matches `## NotesAndStuff` or `### Notes`."
  [body]
  (let [m (re-matcher #"(?m)^## Notes[ \t]*$" body)]
    (when (.find m)
      (let [after  (.end m)
            next-m (re-matcher #"(?m)^## " body)
            end    (if (.find next-m after)
                     (.start next-m)
                     (count body))]
        {:start (.start m) :end end}))))

(defn append-note
  "Append a timestamped note (`**<iso>**\\n\\n<content>`) under the body's
   `## Notes` section. Creates the section at the end of the body when
   missing. The note always lands inside the Notes section even when
   other `## ...` sections follow it. The returned body always ends with
   a single trailing newline."
  [body iso content]
  (let [body* (or body "")
        block (note-block iso content)]
    (if-let [{:keys [start end]} (notes-region body*)]
      (let [head     (subs body* 0 start)
            section  (subs body* start end)
            tail     (subs body* end)
            ;; trim trailing whitespace from the section so the new block
            ;; has a predictable separator regardless of how the existing
            ;; section ended (`## Notes\n`, `## Notes\n\n`, or with prior
            ;; notes).
            section* (str/replace section #"\s+$" "")
            ;; one trailing newline when Notes is the last section; a
            ;; blank-line separator before the next section otherwise.
            sep-tail (if (str/blank? tail) "\n" "\n\n")]
        (str head section* "\n\n" block sep-tail tail))
      (let [head (str/replace body* #"\s+$" "")
            sep  (if (str/blank? head) "" "\n\n")]
        (str head sep notes-heading "\n\n" block "\n")))))

(defn latest-note-content
  "Extract the content of the most recent note under `## Notes` in `body`.
   Returns nil when there is no `## Notes` section or no `**<iso>**`
   block within it. Notes are stored as `**<iso>**\\n\\n<content>` and
   appended in chronological order, so the last block is the newest.
   The returned string is whitespace-trimmed.

   The header regex requires an ISO-8601 timestamp (`YYYY-MM-DDT...`) so
   that standalone bold lines inside note content (e.g. `**Caveat:**`,
   `**TODO:**`) are not misread as note headers — the append-note format
   always emits an ISO timestamp, so this matches the actual contract."
  [body]
  (when-let [{:keys [start end]} (notes-region (or body ""))]
    (let [section (subs body start end)
          m       (re-matcher #"(?m)^\*\*(\d{4}-\d{2}-\d{2}T[^*\n]+)\*\*\s*$" section)
          last-after (loop [last-end nil]
                       (if (.find m)
                         (recur (.end m))
                         last-end))]
      (when last-after
        (let [content (subs section last-after)
              trimmed (str/trim content)]
          (when (seq trimmed) trimmed))))))

(def ^:private prefix-fallback
  "Literal default used when a directory name yields no alphanumeric content
   (empty, whitespace-only, or pure punctuation)."
  "knot")

(defn derive-prefix
  "Derive a project shortcode from a directory name.
   Splits on any run of non-alphanumerics and takes the first letter of
   each segment, lowercased. With a single segment, falls back to the
   first 3 chars. With no alphanumeric content (empty, whitespace, or
   pure punctuation), falls back to the literal `prefix-fallback`.
   Always returns a non-empty `[a-z0-9]+` string — guarantees IDs and
   filenames never start with `-` or contain a space."
  [dir-name]
  (let [lower    (some-> dir-name str/lower-case)
        segments (when lower
                   (->> (str/split lower #"[^a-z0-9]+")
                        (remove empty?)))]
    (cond
      (empty? segments)      prefix-fallback
      (= 1 (count segments)) (let [s (first segments)]
                               (subs s 0 (min 3 (count s))))
      :else                  (apply str (map first segments)))))
