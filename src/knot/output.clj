(ns knot.output
  "Renderers: human (markdown/text + ANSI), JSON, dep-tree.
   stdout = data; stderr = warnings/errors."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [knot.ticket :as ticket]))

(defn- extract-title
  "Return the H1 title from a markdown body (the first `# ...` line),
   trimmed. Returns nil when no H1 is present."
  [body]
  (when body
    (some (fn [line]
            (let [m (re-matches #"#\s+(.*)" line)]
              (when m (str/trim (second m)))))
          (str/split-lines body))))

(defn- inverse-line
  "Format a single inverse entry as `- <id>  <title>` or
   `- <id>  [missing]`."
  [{:keys [id ticket missing?]}]
  (str "- " id "  "
       (cond
         missing?  "[missing]"
         :else     (or (extract-title (:body ticket)) ""))))

(def ^:private inverse-section-order
  "Canonical render order: Blockers, Blocking, Children, Linked."
  [[:blockers "## Blockers"]
   [:blocking "## Blocking"]
   [:children "## Children"]
   [:linked   "## Linked"]])

(defn- render-inverse-sections
  "Build the trailing inverse-section markdown for `show-text`. Empty
   sections are omitted; sections are separated by a blank line. Returns
   `\"\"` when every section is empty."
  [inverses]
  (let [parts (for [[k header] inverse-section-order
                    :let [entries (get inverses k)]
                    :when (seq entries)]
                (str header "\n\n"
                     (str/join "\n" (map inverse-line entries))
                     "\n"))]
    (if (empty? parts)
      ""
      (str "\n" (str/join "\n" parts)))))

(defn show-text
  "Render a ticket map for the `show` command. Returns a string containing
   the YAML frontmatter, the markdown body, and (when supplied) the four
   computed inverse sections — `## Blockers`, `## Blocking`, `## Children`,
   `## Linked` — appended after the body. Each inverse entry is
   `{:id ... :ticket <full-ticket>}` for a resolved ref or
   `{:id ... :missing? true}` for a broken one. Empty sections are omitted."
  ([ticket]
   (ticket/render ticket))
  ([ticket inverses]
   (str (ticket/render ticket)
        (render-inverse-sections inverses))))

(def ^:private ansi-codes
  "Map of friendly names to ANSI SGR parameters (numbers as strings)."
  {:reset  "0"
   :bold   "1"
   :faint  "2"
   :dim    "2"
   :red    "31"
   :yellow "33"
   :cyan   "36"})

(def ^:private ansi-reset "[0m")

(defn tty?
  "True when stdout is connected to a terminal. Uses System/console which
   returns nil when stdout is piped or redirected."
  []
  (some? (System/console)))

(defn color-enabled?
  "Return true when ANSI color output is appropriate.
   `opts` may include :tty?, :no-color?, :no-color-env. Missing keys fall
   back to the actual environment (System/console, NO_COLOR env var). Per
   no-color.org, NO_COLOR disables color when set to any non-empty value."
  ([] (color-enabled? {}))
  ([opts]
   (let [tty?* (if (contains? opts :tty?)
                 (boolean (:tty? opts))
                 (tty?))
         no-color? (boolean (:no-color? opts))
         env (if (contains? opts :no-color-env)
               (:no-color-env opts)
               (System/getenv "NO_COLOR"))
         env-disables? (and (string? env) (not= env ""))]
     (and tty?* (not no-color?) (not env-disables?)))))

(defn- jsonify-ticket
  "Project a `{:frontmatter ... :body ...}` map into the JSON shape used by
   `show --json` and `ls --json`. Keeps frontmatter keys at the top level
   and adds the body as a `body` field. Frontmatter keys are already
   snake_case in the on-disk YAML and pass straight through.
   The frontmatter map is passed through unchanged so the ordered-map type
   produced by `clj-yaml` survives into Cheshire — keys serialize in the
   on-disk order. `(into {} ordered-map)` would silently rebuild as a
   hash-map past 8 entries and scramble the order."
  [{:keys [frontmatter body] :as _ticket} {:keys [include-body?]
                                           :or {include-body? true}}]
  (cond-> frontmatter
    include-body? (assoc :body body)))

(defn- jsonify-inverse-entry
  "Project an inverse-section entry into the JSON shape: resolved entries
   carry `{id, title, status}`; missing entries carry `{id, missing:true}`."
  [{:keys [id ticket missing?]}]
  (if missing?
    {:id id :missing true}
    {:id     id
     :title  (or (extract-title (:body ticket)) "")
     :status (get-in ticket [:frontmatter :status])}))

(defn- inverses->json-fields
  [inverses]
  (when inverses
    {:blockers (mapv jsonify-inverse-entry (:blockers inverses))
     :blocking (mapv jsonify-inverse-entry (:blocking inverses))
     :children (mapv jsonify-inverse-entry (:children inverses))
     :linked   (mapv jsonify-inverse-entry (:linked   inverses))}))

(defn show-json
  "Render a ticket map as a bare JSON object. Keys are snake_case; no
   envelope is added around the object. With `inverses`, adds top-level
   `blockers`, `blocking`, `children`, `linked` arrays whose entries are
   `{id, title, status}` for resolved refs or `{id, missing:true}` for
   broken ones."
  ([ticket]
   (json/generate-string (jsonify-ticket ticket {:include-body? true})))
  ([ticket inverses]
   (json/generate-string
    (merge (jsonify-ticket ticket {:include-body? true})
           (inverses->json-fields inverses)))))

(defn ls-json
  "Render a sequence of ticket maps as a bare JSON array of objects.
   Keys are snake_case; the body is omitted from each entry to keep
   list output compact."
  [tickets]
  (json/generate-string
   (mapv #(jsonify-ticket % {:include-body? false}) tickets)))

(defn colorize
  "Wrap `s` in an ANSI SGR sequence built from `codes` when `color?` is true.
   Returns `s` unchanged when `color?` is false or `codes` is empty.
   `codes` is a sequence of keys from `ansi-codes`."
  [color? codes s]
  (if (or (not color?) (empty? codes))
    s
    (let [params (->> codes (keep ansi-codes) (str/join ";"))]
      (if (str/blank? params)
        s
        (str "[" params "m" s ansi-reset)))))

(def ^:private ls-columns
  [{:key :id        :header "ID"       :align :left}
   {:key :status    :header "STATUS"   :align :left}
   {:key :priority  :header "PRI"      :align :right}
   {:key :mode      :header "MODE"     :align :left}
   {:key :type      :header "TYPE"     :align :left}
   {:key :assignee  :header "ASSIGNEE" :align :left}
   {:key :title     :header "TITLE"    :align :left}])

(def ^:private col-sep "  ")
(def ^:private col-sep-len (count col-sep))

(defn- value-of
  "Plain string for a single ls cell — no padding, no color."
  [ticket k]
  (case k
    :title (or (extract-title (:body ticket)) "")
    (let [v (get (:frontmatter ticket) k)]
      (if (some? v) (str v) ""))))

(defn- pad
  [s width align]
  (let [padding (max 0 (- width (count s)))
        spaces  (apply str (repeat padding \space))]
    (case align
      :right (str spaces s)
      :left  (str s spaces))))

(defn- truncate
  [s width]
  (if (<= (count s) width) s (subs s 0 width)))

(defn- color-codes-for
  [k value]
  (case k
    :status   (case value
                "open"        [:cyan]
                "in_progress" [:yellow]
                "closed"      [:dim]
                [])
    :priority (if (= "0" value) [:red :bold] [])
    :mode     [:faint]
    :type     [:faint]
    []))

(defn- node-label
  "Format a single dep-tree node line: `<id>  <title>` (or `[missing]`),
   with a trailing ` ↑` for seen-before? leaves."
  [{:keys [id ticket missing? seen-before?]}]
  (cond
    missing?     (str id "  [missing]")
    seen-before? (str id "  " (or (extract-title (:body ticket)) "") " ↑")
    :else        (str id "  " (or (extract-title (:body ticket)) ""))))

(defn- render-tree-lines
  "Recursively render a dep-tree node into a flat seq of strings using
   box-drawing characters. `prefix` is the cumulative whitespace/`│`
   stack that comes before the connector; `connector` is `├── `, `└── `,
   or `\"\"` for the root. `child-prefix` is what every descendant of
   this node will inherit (no connector yet)."
  [node prefix connector child-prefix]
  (let [head     (str prefix connector (node-label node))
        children (or (:children node) [])
        n        (count children)]
    (cons head
          (mapcat (fn [i c]
                    (let [last? (= i (dec n))
                          c-conn  (if last? "└── " "├── ")
                          c-cp    (if last? "    " "│   ")]
                      (render-tree-lines c child-prefix c-conn
                                         (str child-prefix c-cp))))
                  (range n)
                  children))))

(defn dep-tree-text
  "Render a dep-tree node (from `knot.query/dep-tree`) as ASCII text using
   box-drawing characters. Seen-before nodes are flagged with ` ↑`;
   missing referents render as `<id>  [missing]`."
  [tree]
  (str/join "\n" (render-tree-lines tree "" "" "")))

(defn- jsonify-tree-node
  "Project a dep-tree node into the JSON map shape: `{id, title?, status?,
   missing?, seen_before?, deps?}`. Title/status are derived from the
   embedded ticket and omitted when the node is `:missing?`. `:deps`
   recurses for normal nodes; omitted for missing or seen-before leaves."
  [{:keys [id ticket children missing? seen-before?]}]
  (cond-> {:id id}
    missing?
    (assoc :missing true)

    (and (not missing?) ticket)
    (merge {:title  (or (extract-title (:body ticket)) "")
            :status (get-in ticket [:frontmatter :status])})

    seen-before?
    (assoc :seen_before true)

    (and (not missing?) (not seen-before?))
    (assoc :deps (mapv jsonify-tree-node (or children [])))))

(defn dep-tree-json
  "Render a dep-tree node as a bare nested JSON object (no envelope).
   Each node carries `id`; non-missing nodes also carry `title` and
   `status`; non-leaf nodes carry a `deps` array of children. Missing
   referents add `missing:true` and stop. Seen-before? nodes add
   `seen_before:true` and stop."
  [tree]
  (json/generate-string (jsonify-tree-node tree)))

(defn ls-table
  "Render `tickets` as a fixed-width text table with the columns
     ID  STATUS  PRI  MODE  TYPE  ASSIGNEE  TITLE
   PRI is right-aligned. TITLE is full-width when piped (`:tty? false`)
   and truncated to fit `:width` (default 80) when `:tty? true`.
   Pass `:color? true` to apply ANSI color to data rows; the header row
   is always plain text."
  [tickets {:keys [color? tty? width]
            :or {color? false tty? false width 80}}]
  (let [non-title        (vec (butlast ls-columns))
        title-col        (last ls-columns)
        non-title-widths (mapv (fn [{:keys [key header]}]
                                 (apply max (count header)
                                        (map #(count (value-of % key)) tickets)))
                               non-title)
        prefix-width     (+ (apply + non-title-widths)
                            (* col-sep-len (count non-title)))
        natural-title    (apply max (count (:header title-col))
                                (map #(count (value-of % :title)) tickets))
        title-budget     (if tty?
                           (max 0 (- width prefix-width))
                           natural-title)
        widths           (conj non-title-widths title-budget)
        format-cell      (fn [col w v color-on?]
                           (let [t (truncate v w)
                                 p (pad t w (:align col))]
                             (if color-on?
                               (colorize color?
                                         (color-codes-for (:key col) t)
                                         p)
                               p)))
        format-row       (fn [color-on? raw-row]
                           (str/join col-sep
                                     (map (fn [col w v]
                                            (format-cell col w v color-on?))
                                          ls-columns widths raw-row)))
        header-row (format-row false (mapv :header ls-columns))
        data-rows  (map (fn [t]
                          (format-row true
                                      (mapv #(value-of t (:key %)) ls-columns)))
                        tickets)]
    (str/join "\n" (cons header-row data-rows))))
