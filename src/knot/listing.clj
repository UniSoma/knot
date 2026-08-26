(ns knot.listing
  "The view (CONTEXT.md): one pipeline from the corpus to the rows a
   listing command renders. `rows` runs it —
   source → closure → component → display filters → sort → limit → columns —
   and `columns` declares every computed column once, so output and help
   iterate the declarations instead of branching per column."
  (:require [knot.acceptance :as acceptance]
            [knot.query :as query]))

(defn- id [t] (get-in t [:frontmatter :id]))

(defn closed?
  "True when the ticket's `:status` is in `terminal-statuses`."
  [terminal-statuses t]
  (contains? (or terminal-statuses #{}) (get-in t [:frontmatter :status])))

(defn by-closed-desc
  "Sort comparator: tickets with a `:closed` timestamp first (newest to
   oldest), then tickets without a stamp last in stable input order."
  [a b]
  (let [ca (get-in a [:frontmatter :closed])
        cb (get-in b [:frontmatter :closed])]
    (cond
      (and ca cb)       (compare cb ca)
      (and ca (nil? cb)) -1
      (and cb (nil? ca)) 1
      :else              0)))

(def ^:private sources
  "Where each view starts. `:list` keeps its documented quirk: the whole
   corpus when the filters carry `:status`, else the live tickets. Only
   `:closed` declares an `:order`; the others keep their source order."
  {:list    {:start (fn [corpus terminal-statuses filters]
                      (if (contains? filters :status)
                        corpus
                        (query/non-terminal corpus terminal-statuses)))}
   :ready   {:start (fn [corpus terminal-statuses _] (query/ready corpus terminal-statuses))}
   :blocked {:start (fn [corpus terminal-statuses _] (query/blocked corpus terminal-statuses))}
   :closed  {:start (fn [corpus terminal-statuses _]
                      (filter (partial closed? terminal-statuses) corpus))
             :order by-closed-desc}})

(def ^:private live-sources
  "The views that start from live tickets and therefore attach the graph
   metrics."
  #{:list :ready :blocked})

(defn criteria
  "Project the filter-relevant keys out of `opts` into the criteria map
   accepted by `query/filter-tickets`. Empty/nil values are dropped so the
   primitive treats absent flags as no-filter."
  [opts]
  (into {}
        (keep (fn [k]
                (when-let [v (get opts k)]
                  (when (seq v) [k v]))))
        [:status :assignee :tag :type :mode :priority :acceptance-complete
         :parent]))

(defn- closure-filter
  "When `scope` carries resolved `:closure` seed ids, restrict `tickets` to
   members of the undirected transitive closure of those seeds over the
   `:via` axes (default: all three), computed across the full `corpus`.
   No-op when `:closure` is absent. The corpus, not `tickets`, drives the
   walk so membership stays graph-faithful regardless of each view's
   display filter."
  [tickets corpus scope]
  (if-let [seeds (seq (:closure scope))]
    (let [members (query/closure-set corpus seeds
                                     (or (:via scope) #{:parent :deps :links}))]
      (filter #(contains? members (id %)) tickets))
    tickets))

(defn- component-filter
  "When `scope` carries a resolved `:component` seed id, restrict `tickets`
   to members of the seed's LIVE-INDUCED connected component (over
   `:parent` ∪ `:deps` ∪ `:links`, closed non-conductive), computed across
   the full `corpus`. No-op when `:component` is absent. Like
   `closure-filter`, the corpus drives membership so the set stays the
   partition the `CC` column promises, independent of each view's
   display filter.

   A closed (terminal-status) seed is a fail-fast error: it is not a node
   in the live-induced graph, so its component is undefined. Returning an
   empty list silently would leave the user's 'show me this cluster' model
   broken with no explanation (ADR 0014)."
  [tickets corpus terminal-statuses scope]
  (if-let [seed (:component scope)]
    (do
      (when (some #(and (= seed (id %)) (closed? terminal-statuses %)) corpus)
        (throw (ex-info (str "--component seed " seed
                             " is closed; it has no live component")
                        {:component seed})))
      (let [members (query/live-component corpus seed terminal-statuses)]
        (filter #(contains? members (id %)) tickets)))
    tickets))

(defn- apply-limit
  "Take the first `n` items of `xs` when `n` is a positive integer. `nil`
   means no limit — return `xs` unchanged. Any other value (including 0
   and negatives) throws: `--limit 0` silently meaning 'no limit' surprised
   users coming from CLIs where 0 means 'zero results'."
  [xs n]
  (cond
    (nil? n)                    xs
    (and (integer? n) (pos? n)) (vec (take n xs))
    :else
    (throw (ex-info (str "--limit must be a positive integer; got " n)
                    {:limit n}))))

(defn- int-or-dash
  "`:cell` for a metric stored under `k`: the number, or `-` when nil."
  [k]
  (fn [row] (if-let [n (get row k)] (str n) "-")))

(defn- when-attached
  "`:json` for a metric stored under `k`: the field only when the view
   attached it — `nil` is still emitted then, for a uniform shape."
  [k]
  (fn [row] (when (contains? row k) {k (get row k)})))

(defn- attach-per-row
  "`:attach` computing `(f corpus id terminal-statuses)` for each row."
  [k f]
  (fn [rows corpus terminal-statuses]
    (mapv #(assoc % k (f corpus (id %) terminal-statuses)) rows)))

(defn- attach-from-map
  "`:attach` computing `(f corpus terminal-statuses)` ONCE — an id → value
   map over the whole corpus — then reading each row's entry."
  [k f]
  (fn [rows corpus terminal-statuses]
    (let [m (f corpus terminal-statuses)]
      (mapv #(assoc % k (get m (id %))) rows))))

(def ^:private all-sources (set (keys sources)))

(defn attach-children-progress
  "Attach `:children-progress [terminal total]` to each umbrella in `rows`
   — those with at least one direct child anywhere in `corpus` (the full
   live+archive set, so closed children still count). Non-umbrellas are
   left untouched, so the key's absence doubles as the non-umbrella
   predicate the cell and JSON key on. Public because `show` attaches it
   to a single ticket outside any view."
  [rows corpus terminal-statuses]
  (mapv (fn [t]
          (let [[_ total :as cp] (query/children-progress corpus (id t) terminal-statuses)]
            (cond-> t (pos? total) (assoc :children-progress cp))))
        rows))

(def columns
  "One declaration per computed column, in table-layout order. Fields:
   `:key` `:header` `:align` as the table renders them; `:position`
   `:leading` for the column that precedes ID (all others sit between AGE
   and TITLE); `:sources` the views that attach the column; `:attach`
   `(fn [rows corpus terminal-statuses])` adding the row key, absent for a
   column read straight off frontmatter; `:shown?` `(fn [rows])` deciding
   whether the column appears at all; `:cell` `(fn [row])` the plain cell
   string; `:json` `(fn [row])` the fields the row's JSON gains, or nil.

   The graph metrics (`:sources` = `live-sources`) are computed over the
   whole live-induced graph of the corpus, so a blocker outside the view
   still counts and the CC ordinal is filter-independent. LVL is shown on
   key presence — `nil` is legitimate for a live deps cycle and that dash
   is information — while CC needs a non-nil ordinal somewhere, or an
   all-singleton view would show an all-dash column."
  [{:key :acceptance :header "AC" :align :left
    :sources all-sources
    :shown? (fn [rows] (some #(seq (get-in % [:frontmatter :acceptance])) rows))
    :cell   (fn [row] (if-let [ac (seq (get-in row [:frontmatter :acceptance]))]
                        (let [[d t] (acceptance/progress ac)] (str d "/" t))
                        "-"))
    :json   (constantly nil)}
   {:key :children :header "CHLD" :align :left
    :sources all-sources
    ;; Only umbrellas gain the key, so its absence doubles as the
    ;; non-umbrella predicate the cell and JSON key on.
    :attach attach-children-progress
    :shown? (fn [rows] (some :children-progress rows))
    :cell   (fn [row] (if-let [[term total] (:children-progress row)] (str term "/" total) "-"))
    :json   (fn [row] (when-let [[term total] (:children-progress row)]
                        {:children_total total :children_terminal term}))}
   {:key :leverage :header "LEV" :align :right
    :sources live-sources
    :attach (attach-per-row :leverage query/leverage)
    :shown? (fn [rows] (some #(contains? % :leverage) rows))
    :cell   (int-or-dash :leverage)
    :json   (when-attached :leverage)}
   {:key :coupling :header "CPL" :align :right
    :sources live-sources
    :attach (attach-per-row :coupling query/coupling)
    :shown? (fn [rows] (some #(contains? % :coupling) rows))
    :cell   (int-or-dash :coupling)
    :json   (when-attached :coupling)}
   {:key :level :header "LVL" :align :right
    :sources live-sources
    :attach (attach-from-map :level query/levels)
    :shown? (fn [rows] (some #(contains? % :level) rows))
    :cell   (int-or-dash :level)
    :json   (when-attached :level)}
   {:key :cc :header "CC" :align :left :position :leading
    :sources live-sources
    :attach (attach-from-map :cc query/connected-components)
    :shown? (fn [rows] (some #(some? (:cc %)) rows))
    :cell   (int-or-dash :cc)
    :json   (when-attached :cc)}])

(defn- attach-columns
  [rows corpus terminal-statuses source]
  (reduce (fn [rows {:keys [attach sources]}]
            (if (and attach (contains? sources source))
              (attach rows corpus terminal-statuses)
              rows))
          rows
          columns))

(defn rows
  "Run the view `{:source :scope :filters :limit}` over `corpus` and return
   its rows. `:source` is one of `:list` `:ready` `:blocked` `:closed`;
   `:scope` may carry `:closure` seed ids with `:via` axes and/or a
   `:component` seed id; `:filters` is a `criteria` map; `:limit` truncates
   after filtering and sorting."
  [corpus terminal-statuses {:keys [source scope filters limit]}]
  (let [{:keys [start order]} (get sources source)
        _        (when-not start
                   (throw (ex-info (str "unknown view source " source) {:source source})))
        base     (start corpus terminal-statuses filters)
        scoped   (-> base
                     (closure-filter corpus scope)
                     (component-filter corpus terminal-statuses scope))
        visible  (query/filter-tickets scoped filters)
        sorted   (if order (sort order visible) visible)]
    (attach-columns (apply-limit sorted limit) corpus terminal-statuses source)))
