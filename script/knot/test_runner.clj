(ns knot.test-runner
  "Parallel test runner for `bb test`.

   The suite is dominated by end-to-end tests that shell out to a fresh
   `bb` per command (~890 subprocesses, ~90ms each). Run one at a time
   that is pure wall-clock waste on a multi-core box, so this runner
   fans test vars out over a worker pool.

   What the runner does NOT change: `:once` fixtures still wrap their
   whole namespace, `:each` fixtures still wrap every var, every var
   reports into its own counters ref (summed at the end, so the totals
   match a serial run exactly), and failure output is captured per var
   and replayed grouped by namespace.

   Isolation: `with-redefs` rebinds a var process-wide, so a namespace
   that uses it cannot run next to anything else. Those namespaces are
   detected by scanning their source (see `unsafe-ns?`) and run alone,
   in a serial phase after the parallel one. Keep that detection in mind
   before introducing any other global mutation into a test."
  (:require [clojure.string :as str]
            [clojure.test :as t]))

(def ^:private global-mutation-markers
  ["with-redefs" "alter-var-root" "System/setProperty"])

(defn- unsafe-ns?
  "True when the test source mutates process-global state and therefore
   must not run concurrently with any other test."
  [file]
  (let [src (slurp file)]
    (boolean (some #(str/includes? src %) global-mutation-markers))))

(defn- test-vars-of [ns-sym]
  (->> (ns-interns (find-ns ns-sym))
       vals
       (filter #(:test (meta %)))
       (sort-by #(:line (meta %)))))

(defn- run-with-counters
  "Run `f` with fresh report counters and captured output.
   Returns {:counters {...} :output \"...\"}."
  [f]
  (let [sw (java.io.StringWriter.)]
    ;; `*err*` is captured too: tests that deliberately print diagnostics
    ;; to stderr would otherwise leak straight out of a worker thread,
    ;; interleaved with other workers and detached from their namespace
    ;; header.
    (binding [t/*report-counters* (ref t/*initial-report-counters*)
              t/*test-out*        sw
              *out*               sw
              *err*               sw]
      (try
        (f)
        (catch Throwable e
          (t/do-report {:type     :error
                        :message  "Uncaught exception outside of a test"
                        :expected nil
                        :actual   e})))
      {:counters @t/*report-counters*
       :output   (str sw)})))

(defn- work-items
  "Work items for `ns-sym`: one per test var, or a single namespace-wide
   item when `:once` fixtures are declared (they must wrap every var)."
  [ns-sym]
  (let [nsm  (meta (find-ns ns-sym))
        once (::t/once-fixtures nsm)
        each (t/join-fixtures (::t/each-fixtures nsm))
        vars (test-vars-of ns-sym)]
    (if (seq once)
      [{:ns   ns-sym
        :line 0
        :run  #(run-with-counters
                (fn [] ((t/join-fixtures once)
                        (fn [] (doseq [v vars] (each (fn [] (t/test-var v))))))))}]
      (for [v vars]
        {:ns   ns-sym
         ;; Replayed output is sorted by this, so a failing namespace
         ;; prints its failures in source order on every run rather than
         ;; in whatever order the workers happened to finish.
         :line (:line (meta v))
         :run  #(run-with-counters (fn [] (each (fn [] (t/test-var v)))))}))))

(defn- run-parallel
  "Drain `items` through `n` workers, each pulling and running items
   until the queue is empty."
  [items n]
  (let [q       (atom (vec items))
        poll!   (fn [] (let [[old _] (swap-vals! q #(if (seq %) (subvec % 1) %))]
                         (first old)))
        workers (doall
                 (for [_ (range n)]
                   (future
                     (loop [acc []]
                       (if-let [item (poll!)]
                         (recur (conj acc (assoc item :result ((:run item)))))
                         acc)))))]
    ;; Eager on purpose: a lazy result would let the serial phase start
    ;; while these futures are still running, which is exactly what the
    ;; two-phase split exists to prevent.
    (into [] (mapcat deref) workers)))

(defn- run-serial [items]
  (mapv (fn [item] (assoc item :result ((:run item)))) items))

(defn- worker-count [item-count]
  (let [n (or (some-> (System/getenv "KNOT_TEST_THREADS") parse-long)
              (.availableProcessors (Runtime/getRuntime)))]
    (max 1 (min n item-count))))

(defn run-tests
  "Run every test var in `nses` (a seq of {:ns sym :file path}).
   Returns the clojure.test summary map."
  [nses]
  (let [{unsafe true safe false} (group-by (comp unsafe-ns? :file) nses)
        safe-items    (mapcat (comp work-items :ns) safe)
        unsafe-items  (mapcat (comp work-items :ns) unsafe)
        ;; The phases are sequenced by these two bindings, not by
        ;; argument-evaluation order: the parallel phase is fully drained
        ;; before a single serial item runs.
        par-results   (run-parallel safe-items (worker-count (count safe-items)))
        ser-results   (run-serial unsafe-items)
        results       (into par-results ser-results)
        by-ns         (group-by :ns results)]
    (doseq [{:keys [ns]} nses]
      (println "\nTesting" ns)
      (doseq [{:keys [result]} (sort-by :line (get by-ns ns))]
        (when-not (str/blank? (:output result))
          (print (:output result))
          (flush))))
    (let [summary (->> results
                       (map (comp :counters :result))
                       (reduce (partial merge-with +)
                               {:test 0 :pass 0 :fail 0 :error 0}))]
      (println "\nRan" (:test summary) "tests containing"
               (+ (:pass summary) (:fail summary) (:error summary)) "assertions.")
      (println (:fail summary) "failures," (:error summary) "errors.")
      (assoc summary :type :summary))))
