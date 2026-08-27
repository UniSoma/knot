(ns clj-yaml.core
  "clj-yaml.core for jolt (SnakeYAML is Java). Implements the frontmatter
   subset knot reads and writes: block mappings, block/flow sequences,
   scalars (null/bool/int/float/quoted/plain), literal block scalars.
   Emission mirrors SnakeYAML's block style so files round-trip byte-for-byte."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as om]))

;;; ---- emit -----------------------------------------------------------------

(def ^:private reserved
  #{"" "~" "null" "Null" "NULL" "true" "True" "TRUE" "false" "False" "FALSE"
    "yes" "Yes" "YES" "no" "No" "NO" "on" "On" "ON" "off" "Off" "OFF"})

(defn- needs-quote? [s]
  (or (reserved s)
      (re-matches #"[-+]?(\d[\d_]*|\d*\.\d+|\d+\.\d*)([eE][-+]?\d+)?|0x[0-9a-fA-F]+|0o[0-7]+|\.inf|-\.inf|\.nan" s)
      (re-matches #"\d{4}-\d\d-\d\d.*" s)
      (re-find #"^[\s,\[\]{}#&*!|>'\"%@`]" s)
      (re-find #"^[-?:](\s|$)" s)
      (= s "---")
      (re-find #": |\s#| $|\t" s)
      (str/ends-with? s ":")))

(defn- scalar [v]
  (cond
    (nil? v)     "null"
    (string? v)  (if (needs-quote? v)
                   (str "'" (str/replace v "'" "''") "'")
                   v)
    (keyword? v) (scalar (name v))
    :else        (str v)))

(defn- key-str [k]
  (scalar (if (keyword? k) (name k) (str k))))

(defn- pad [n] (apply str (repeat n " ")))

(defn- emit* [v indent]
  (let [p (pad indent)]
    (cond
      (map? v)
      (if (empty? v)
        [(str p "{}")]
        (mapcat (fn [[k x]]
                  (cond
                    (and (map? x) (seq x))
                    (cons (str p (key-str k) ":") (emit* x (+ indent 2)))
                    (and (sequential? x) (seq x))
                    (cons (str p (key-str k) ":") (emit* x indent))
                    (and (string? x) (str/includes? x "\n"))
                    (let [tail? (str/ends-with? x "\n")
                          body  (if tail? (subs x 0 (dec (count x))) x)
                          ip    (pad (+ indent 2))]
                      (cons (str p (key-str k) ": " (if tail? "|" "|-"))
                            (map #(if (str/blank? %) "" (str ip %))
                                 (str/split body #"\n" -1))))
                    :else
                    [(str p (key-str k) ": " (if (sequential? x) "[]" (scalar x)))]))
                v))
      (sequential? v)
      (mapcat (fn [x]
                (cond
                  (and (coll? x) (seq x))
                  (let [[l & ls] (emit* x (+ indent 2))]
                    (cons (str p "- " (str/triml l)) ls))
                  :else [(str p "- " (if (sequential? x) "[]" (scalar x)))]))
              v)
      :else [(str p (scalar v))])))

(defn generate-string
  [data & _opts]
  (str (str/join "\n" (emit* data 0)) "\n"))

;;; ---- parse ----------------------------------------------------------------

(defn- unquote-double [s]
  (str/replace s #"\\([\\\"/nrt])"
               (fn [[_ c]] (case c "n" "\n" "r" "\r" "t" "\t" c))))

(defn- parse-scalar [s]
  (let [s (str/trim s)]
    (cond
      (or (= s "") (= s "~") (#{"null" "Null" "NULL"} s)) nil
      (#{"true" "True" "TRUE"} s)   true
      (#{"false" "False" "FALSE"} s) false
      (str/starts-with? s "'")
      (-> s (subs 1 (dec (count s))) (str/replace "''" "'"))
      (str/starts-with? s "\"")
      (unquote-double (subs s 1 (dec (count s))))
      (str/starts-with? s "[")
      (let [inner (str/trim (subs s 1 (dec (count s))))]
        (if (= inner "") []
            (mapv parse-scalar (str/split inner #","))))
      (re-matches #"[-+]?\d+" s) (Long/parseLong s)
      (re-matches #"[-+]?(\d+\.\d*|\d*\.\d+)([eE][-+]?\d+)?" s) (Double/parseDouble s)
      :else s)))

(defn- strip-comment
  "Drop a ` #...` trailing comment, honouring single/double quotes."
  [line]
  (if (re-find #"^\s*#" line)
    ""
    (loop [i 0 q nil]
      (if (>= i (count line))
        line
        (let [c (.charAt ^String line i)]
          (cond
            (and q (= c q) (= q \") (pos? i) (= \\ (.charAt ^String line (dec i))))
            (recur (inc i) q)
            (and q (= c q)) (recur (inc i) nil)
            (and (nil? q) (or (= c \') (= c \"))) (recur (inc i) c)
            (and (nil? q) (= c \#) (or (zero? i) (Character/isWhitespace (.charAt ^String line (dec i)))))
            (str/trimr (subs line 0 i))
            :else (recur (inc i) q)))))))

(defn- indent-of [line] (count (re-find #"^ *" line)))

(defn- seq-item? [line] (or (re-find #"^ *- " line) (= (str/trim line) "-")))

(declare parse-block)

(defn- block-scalar
  "Collect a `|`/`>` block scalar body deeper than `parent`. Returns [value rest]."
  [style parent lines]
  (let [body   (vec (take-while #(or (str/blank? %) (> (indent-of %) parent)) lines))
        more   (drop (count body) lines)
        body   (loop [b body] (if (and (seq b) (str/blank? (peek b))) (recur (pop b)) b))
        ind    (if (seq body) (apply min (map indent-of (remove str/blank? body))) 0)
        ls     (map #(if (str/blank? %) "" (subs % ind)) body)
        folded (if (str/starts-with? style ">") (str/join " " ls) (str/join "\n" ls))
        keep?  (not (str/includes? style "-"))]
    [(if keep? (str folded "\n") folded) more]))

(defn- parse-seq [indent lines]
  (loop [items [] lines lines]
    (let [line (first lines)]
      (if (and line (= (indent-of line) indent) (seq-item? line))
        (let [content (str/triml (subs line (min (count line) (+ indent 2))))
              cindent (+ indent 2)]
          (cond
            (str/blank? content)
            (let [[v more] (parse-block (inc indent) (rest lines))]
              (recur (conj items v) more))
            (re-find #"^[^'\"\[]*?:( |$)" content)
            (let [[m more] (parse-block cindent (cons (str (pad cindent) content) (rest lines)))]
              (recur (conj items m) more))
            :else
            (recur (conj items (parse-scalar content)) (rest lines))))
        [items lines]))))

(defn- parse-map [indent lines]
  (loop [m (om/ordered-map) lines lines]
    (let [line (first lines)]
      (if (and line (not (str/blank? line)) (= (indent-of line) indent)
               (not (seq-item? line)))
        (let [[_ k v] (re-find #"^ *('(?:[^']|'')*'|\"(?:[^\"\\]|\\.)*\"|[^:]+?):(?: +(.*))?$" line)
              _       (when-not k (throw (ex-info (str "clj-yaml shim: bad mapping line: " line) {})))
              k       (keyword (str (parse-scalar k)))
              v       (str/trim (or v ""))
              more    (rest lines)]
          (cond
            (re-matches #"[|>][-+]?" v)
            (let [[val more] (block-scalar v indent more)]
              (recur (assoc m k val) more))
            (= v "")
            (let [more (drop-while str/blank? more)
                  nxt  (first more)]
              (cond
                (nil? nxt) (recur (assoc m k nil) more)
                (and (= (indent-of nxt) indent) (seq-item? nxt))
                (let [[val more] (parse-seq indent more)]
                  (recur (assoc m k val) more))
                (> (indent-of nxt) indent)
                (let [[val more] (parse-block (indent-of nxt) more)]
                  (recur (assoc m k val) more))
                :else (recur (assoc m k nil) more)))
            :else (recur (assoc m k (parse-scalar v)) more)))
        [m lines]))))

(defn- parse-block [_indent lines]
  (let [lines (drop-while str/blank? lines)
        line  (first lines)]
    (cond
      (nil? line) [nil lines]
      (seq-item? line) (parse-seq (indent-of line) lines)
      :else (parse-map (indent-of line) lines))))

(defn parse-string
  [s & _opts]
  (let [lines (->> (str/split-lines (or s ""))
                   (map strip-comment)
                   (remove #(re-matches #"^(---|\.\.\.)\s*$" %))
                   vec)]
    (when (some (complement str/blank?) lines)
      (let [[v remaining] (parse-block 0 lines)]
        (when (some (complement str/blank?) remaining)
          (throw (ex-info (str "clj-yaml shim: unparsed YAML at: "
                               (first (remove str/blank? remaining)))
                          {:remaining remaining})))
        v))))
