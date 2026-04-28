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

(defn- random-suffix
  "Generate `random-chars` (2) lowercase Crockford-base32 random chars."
  []
  (let [alphabet-len (count crockford-alphabet)]
    (apply str (repeatedly random-chars
                           #(.charAt crockford-alphabet (rand-int alphabet-len))))))

(defn generate-id
  "Generate a new ticket id of the form `<prefix>-<10 timestamp + 2 random>`."
  [prefix]
  (str prefix "-" (encode-timestamp (System/currentTimeMillis)) (random-suffix)))

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

(defn derive-prefix
  "Derive a project shortcode from a directory name.
   First letter of each `[-_]`-separated segment, lowercased.
   Falls back to the first 3 chars (lowercased) when the result is shorter
   than 2 chars."
  [dir-name]
  (let [lower (str/lower-case dir-name)
        initials (->> (str/split lower #"[-_]")
                      (remove empty?)
                      (map first)
                      (apply str))]
    (if (< (count initials) 2)
      (subs lower 0 (min 3 (count lower)))
      initials)))
