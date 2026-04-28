(ns knot.query
  "Pure graph algorithms over ticket sequences:
   cycle detection, dep-tree, ready/blocked, filters.")

(defn non-terminal
  "Return only those tickets whose `:status` is not in `terminal-statuses`.
   Preserves input order. Tickets without a `:status` are kept (lenient)."
  [tickets terminal-statuses]
  (remove (fn [t]
            (contains? terminal-statuses
                       (get-in t [:frontmatter :status])))
          tickets))

(defn- index-by-id
  "Build an `{id -> ticket}` map from a tickets seq."
  [tickets]
  (into {} (map (fn [t] [(get-in t [:frontmatter :id]) t])) tickets))

(defn- deps-of
  "Read the `:deps` list out of a ticket's frontmatter; nil-safe."
  [ticket]
  (or (get-in ticket [:frontmatter :deps]) []))

(defn- find-path
  "DFS from `start` to `target` in the deps graph defined by `index`.
   Returns the id-vector path `[start ... target]` when found, else nil.
   The visited-set guards against pre-existing cycles in the graph so the
   walk terminates regardless of input shape. Missing nodes (broken refs)
   are treated as having no children."
  [index start target]
  (letfn [(go [node visited]
            (cond
              (= node target)          [node]
              (contains? visited node) nil
              :else
              (let [visited* (conj visited node)
                    children (deps-of (get index node))]
                (some (fn [c]
                        (when-let [sub (go c visited*)]
                          (cons node sub)))
                      children))))]
    (some-> (go start #{}) vec)))

(defn would-create-cycle?
  "Return the offending cycle path as a vector of ids when adding the edge
   `from → to` to `tickets` would introduce a cycle. The path starts and
   ends at `from`. Returns nil when the edge is safe to add. Self-loops
   (from = to) return `[from to]` directly. Missing tickets are treated
   as having no deps — broken refs are not cycles."
  [tickets from to]
  (if (= from to)
    [from to]
    (let [index     (index-by-id tickets)
          path-back (find-path index to from)]
      (when path-back
        (vec (cons from path-back))))))

(defn- terminal-status?
  [terminal-statuses status]
  (contains? terminal-statuses status))

(defn- live?
  "True when ticket's status is not in `terminal-statuses` (i.e., live work)."
  [terminal-statuses ticket]
  (not (terminal-status? terminal-statuses
                         (get-in ticket [:frontmatter :status]))))

(defn- dep-status
  "Lookup the status of the ticket referenced by `dep-id` in `index`.
   Returns nil for missing refs (broken)."
  [index dep-id]
  (get-in (get index dep-id) [:frontmatter :status]))

(defn- all-deps-terminal?
  "True when every entry in the ticket's `:deps` resolves to a ticket whose
   status is in `terminal-statuses`. A missing referent is NOT terminal —
   it blocks readiness, in keeping with broken-ref-as-blocker semantics."
  [terminal-statuses index ticket]
  (let [deps (deps-of ticket)]
    (every? #(terminal-status? terminal-statuses (dep-status index %)) deps)))

(defn- any-dep-non-terminal?
  "True when at least one of the ticket's `:deps` is not in a terminal
   status. Missing referents count as non-terminal."
  [terminal-statuses index ticket]
  (let [deps (deps-of ticket)]
    (boolean (some #(not (terminal-status? terminal-statuses (dep-status index %))) deps))))

(defn- by-priority-then-created-desc
  "Sort comparator key for `ready`: lower priority first (0 = highest),
   then newer `:created` first. Tickets without a priority sort after
   any explicit priority. Tickets without `:created` sort last within
   their priority bucket."
  [t]
  (let [fm (:frontmatter t)
        p  (:priority fm)
        c  (:created fm)]
    [(if (number? p) p Long/MAX_VALUE)
     ;; descending by created → invert via a string compare on a
     ;; placeholder for nils so they sort last
     (or c "")]))

(defn- compare-ready-order
  "Two-arg comparator: priority ascending, then `:created` descending."
  [a b]
  (let [[pa ca] (by-priority-then-created-desc a)
        [pb cb] (by-priority-then-created-desc b)
        cmp     (compare pa pb)]
    (if (zero? cmp)
      (compare cb ca)
      cmp)))

(defn ready
  "Return live (non-terminal) tickets whose every `:deps` entry resolves to
   a terminal-status ticket. Sorted by priority ascending (0 = highest),
   then `:created` descending. Missing dep refs block readiness."
  [tickets terminal-statuses]
  (let [index (index-by-id tickets)
        live  (filter (partial live? terminal-statuses) tickets)]
    (->> live
         (filter (partial all-deps-terminal? terminal-statuses index))
         (sort compare-ready-order)
         vec)))

(defn blocked
  "Return live (non-terminal) tickets that have at least one `:deps` entry
   whose referent is not in a terminal status. Missing refs count as
   non-terminal (broken-ref-as-blocker). Same sort order as `ready`."
  [tickets terminal-statuses]
  (let [index (index-by-id tickets)
        live  (filter (partial live? terminal-statuses) tickets)]
    (->> live
         (filter (partial any-dep-non-terminal? terminal-statuses index))
         (sort compare-ready-order)
         vec)))

(defn broken-refs
  "Return a vector of broken reference descriptors for `ticket` against
   the id-set defined by `tickets`. Each entry is `{:kind <:deps|:parent>
   :id <missing-id>}`. The deps refs appear in declaration order before
   the parent ref. Empty when `ticket` has no broken refs."
  [ticket tickets]
  (let [known (into #{} (map #(get-in % [:frontmatter :id])) tickets)
        fm    (:frontmatter ticket)
        dep-refs   (for [d (or (:deps fm) [])
                         :when (not (contains? known d))]
                     {:kind :deps :id d})
        parent-id  (:parent fm)
        parent-ref (when (and parent-id (not (contains? known parent-id)))
                     {:kind :parent :id parent-id})]
    (vec (cond-> dep-refs
           parent-ref (concat [parent-ref])))))

(defn dep-tree
  "Build a nested data structure rooted at `root-id` showing the deps
   subtree. Each node is `{:id ... :ticket ... :children [...]}`.

   Default mode dedupes: an id seen earlier in the left-to-right walk
   yields a leaf `{:id ... :ticket ... :seen-before? true}` with no
   children — used by the renderer to mark with `↑`. With `:full? true`
   in `opts`, every occurrence is expanded fully; only true cycles
   (ids in the current ancestor path) are broken with `:seen-before?
   true` to prevent infinite recursion.

   Missing referents (broken refs, including a missing root) yield
   `{:id ... :missing? true}` — no `:ticket`, no `:children`."
  [tickets root-id {:keys [full?] :or {full? false}}]
  (let [index (index-by-id tickets)]
    (letfn [(build [id ancestors seen]
              (cond
                (nil? (get index id))
                [{:id id :missing? true} seen]

                (and (not full?) (contains? seen id))
                [{:id id :ticket (get index id) :seen-before? true} seen]

                (and full? (contains? ancestors id))
                [{:id id :ticket (get index id) :seen-before? true} seen]

                :else
                (let [t     (get index id)
                      deps  (deps-of t)
                      seen* (conj seen id)
                      anc*  (conj ancestors id)
                      [children seen-out]
                      (reduce (fn [[acc s] d]
                                (let [[child s*] (build d anc* s)]
                                  [(conj acc child) s*]))
                              [[] seen*]
                              deps)]
                  [{:id id :ticket t :children children} seen-out])))]
      (first (build root-id #{} #{})))))

(defn project-cycles
  "Return a vector of cycle paths found anywhere in the deps graph defined
   by `tickets`. Each cycle path is a vector of ids `[v ... v]` whose first
   and last elements are equal. Self-loops appear as `[id id]`. Missing
   refs do not produce phantom cycles. Order is deterministic per the
   DFS traversal — tests should canonicalize before equality."
  [tickets]
  (let [index (index-by-id tickets)
        ids   (mapv #(get-in % [:frontmatter :id]) tickets)]
    (loop [remaining ids
           color     {}
           cycles    []]
      (if-let [start (first remaining)]
        (if (contains? color start)
          (recur (rest remaining) color cycles)
          (let [;; iterative DFS: stack frames carry (node, deps-iterator).
                ;; on first visit we mark :gray and push children; on pop
                ;; we mark :black. when a child is :gray, that's a cycle.
                [color* cycles*]
                (loop [stack  [[start (seq (deps-of (get index start))) [start]]]
                       color  (assoc color start :gray)
                       cycles cycles]
                  (if (empty? stack)
                    [color cycles]
                    (let [[node deps path] (peek stack)]
                      (if-let [child (first deps)]
                        (let [stack* (conj (pop stack)
                                           [node (next deps) path])]
                          (cond
                            (= :gray (color child))
                            (let [idx        (.indexOf ^java.util.List path child)
                                  cycle-path (-> (subvec path idx)
                                                 (conj child))]
                              (recur stack* color (conj cycles cycle-path)))

                            (= :black (color child))
                            (recur stack* color cycles)

                            (nil? (get index child))
                            ;; broken ref: skip without coloring
                            (recur stack* color cycles)

                            :else
                            (recur (conj stack* [child
                                                 (seq (deps-of (get index child)))
                                                 (conj path child)])
                                   (assoc color child :gray)
                                   cycles)))
                        ;; no more deps at this frame: mark black and pop
                        (recur (pop stack) (assoc color node :black) cycles)))))]
            (recur (rest remaining) color* cycles*)))
        cycles))))
