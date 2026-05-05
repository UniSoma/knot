(ns knot.store
  "Filesystem boundary: load-all/load-one/save!. Centralizes
   :updated/:closed timestamping, archive auto-move, symmetric link
   maintenance."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [flatland.ordered.map :as om]
            [knot.ticket :as ticket])
  (:import (java.nio.file CopyOption FileAlreadyExistsException Files
                          OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util UUID)))

(def archive-subdir "archive")

(defn ticket-path
  "Compute the live-directory path of a ticket from its id and slug.
   Returns `<project-root>/<tickets-dir>/<id>--<slug>.md`, or
   `<project-root>/<tickets-dir>/<id>.md` when the slug is blank."
  [project-root tickets-dir id slug]
  (let [filename (if (str/blank? slug)
                   (str id ".md")
                   (str id "--" slug ".md"))]
    (str (fs/path project-root tickets-dir filename))))

(defn- archive-path
  "Compute the archive-directory path of a ticket from its id and slug."
  [project-root tickets-dir id slug]
  (let [filename (if (str/blank? slug)
                   (str id ".md")
                   (str id "--" slug ".md"))]
    (str (fs/path project-root tickets-dir archive-subdir filename))))

(defn- terminal? [terminal-statuses status]
  (contains? (or terminal-statuses #{}) status))

(defn- find-by-id-glob-all
  "Return every path matching `<id>.md` or `<id>--*.md` under `dir`."
  [dir id]
  (when (fs/directory? dir)
    (let [bare    (fs/path dir (str id ".md"))
          slugged (fs/glob dir (str id "--*.md"))]
      (concat (when (fs/exists? bare) [(str bare)])
              (map str slugged)))))

(defn- find-all-paths
  "Return every on-disk path for `id` across both live and archive
   directories. Live entries come first."
  [project-root tickets-dir id]
  (concat
   (find-by-id-glob-all (fs/path project-root tickets-dir) id)
   (find-by-id-glob-all (fs/path project-root tickets-dir archive-subdir) id)))

(defn find-existing-path
  "Locate the on-disk file for `id` across both live and archive directories.
   Returns the path string of the first match (live wins), or nil when no
   matching file exists."
  [project-root tickets-dir id]
  (first (find-all-paths project-root tickets-dir id)))

(defn- assoc-ordered
  "Like `assoc`, but inserts the key at the end of an ordered map when
   absent, preserving canonical ordering provided by the caller."
  [m k v]
  (if (contains? m k)
    (assoc m k v)
    (assoc (or m (om/ordered-map)) k v)))

(defn- assoc-after
  "Insert `[k v]` into ordered map `m` immediately after `after-k`. If
   `k` already exists, update in place. If `after-k` is missing, append."
  [m after-k k v]
  (cond
    (contains? m k)             (assoc m k v)
    (not (contains? m after-k)) (assoc-ordered m k v)
    :else
    (reduce (fn [acc [k* v*]]
              (let [acc (assoc acc k* v*)]
                (if (= k* after-k) (assoc acc k v) acc)))
            (om/ordered-map)
            m)))

(defn- now-iso []
  (str (Instant/now)))

(defn- stamp-timestamps
  "Return an updated frontmatter map with :updated bumped to `now`, and
   :closed set/cleared based on the new vs prior status. When :closed is
   stamped fresh, it is inserted immediately after :updated for stable
   human-readable ordering."
  [fm new-status prior-status* now terminal-statuses]
  (let [bumped          (assoc-ordered fm :updated now)
        new-terminal?   (terminal? terminal-statuses new-status)
        prior-terminal? (and prior-status* (terminal? terminal-statuses prior-status*))
        same-terminal?  (and new-terminal? prior-terminal? (= new-status prior-status*))]
    (cond
      ;; Crossing into terminal, or same-terminal but :closed somehow missing
      ;; (defensive: terminal status implies :closed is set).
      (and new-terminal?
           (or (not same-terminal?) (not (contains? bumped :closed))))
      (assoc-after bumped :updated :closed now)

      (not new-terminal?)
      (dissoc bumped :closed)

      :else bumped)))

(defn- slug-of-filename
  "Extract the slug suffix from a `<id>--<slug>.md` filename, or nil for a
   bare `<id>.md`."
  [fname]
  (when fname
    (second (re-matches #".+--(.+)\.md" (str fname)))))

(def ^:private atomic-move-options
  "CopyOption[] for `Files/move`. ATOMIC_MOVE asks the kernel to honor
   POSIX `rename(2)` semantics — the destination flips from old contents
   to new in a single filesystem step, with no observer-visible window
   where the file is missing or partial. REPLACE_EXISTING permits the
   destination to pre-exist (the rename overwrites it atomically)."
  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                          StandardCopyOption/REPLACE_EXISTING]))

(def ^:private write-create-new-options
  (into-array OpenOption [StandardOpenOption/CREATE_NEW
                          StandardOpenOption/WRITE]))

(defn- atomic-write!
  "Atomically replace (or create) the file at `path` with `bytes`. Writes
   to a sibling temp file first, then renames into place via ATOMIC_MOVE.
   A crash at any point leaves either the prior file contents or the new
   contents at `path` — never a partial or empty file."
  [^Path path ^bytes bytes]
  (let [parent  (.getParent path)
        tmp-fn  (str ".tmp-" (UUID/randomUUID) "-" (.getFileName path))
        tmp     ^Path (fs/path parent tmp-fn)]
    (Files/write tmp bytes write-create-new-options)
    (try
      (Files/move tmp path atomic-move-options)
      (catch Throwable t
        (Files/deleteIfExists tmp)
        (throw t)))))

(defn- atomic-move!
  "Atomically rename `src` to `dst` via `rename(2)`. The single-syscall
   semantics guarantee that `src` disappears and `dst` takes its content
   in one observable step — no window where the file lives in both places
   or in neither. Wrapped as a private fn so tests can `with-redefs` it
   to simulate filesystem failures."
  [^Path src ^Path dst]
  (Files/move src dst atomic-move-options))

(defn save!
  "Render `ticket` and write it to its target on-disk path under
   `<project-root>/<tickets-dir>/`. Centralizes timestamping and archive
   maintenance:
     - :updated is set to `(:now opts)` (or wall-clock now) on every save.
     - :closed is set when the new status is in `:terminal-statuses` and
       the prior status was not (or was a different terminal); cleared
       when the new status is non-terminal.
     - Files of terminal-status tickets live under `<tickets-dir>/archive/`;
       non-terminal under `<tickets-dir>/`. Any pre-existing file at the
       wrong location is removed (file-location self-healing).
   When `slug` is nil, the slug is recovered from the existing on-disk
   filename — keeping the slug stable across status transitions without
   the caller having to track it.

   **Atomicity** (kno-01kqgqafcxvv): the write is structured so a crash
   at any observable point leaves the ticket file in *exactly one*
   on-disk location — never in two places, never in none. For
   cross-directory transitions (close: live → archive, reopen:
   archive → live) the new content is first written atomically at the
   source path, then a single `rename(2)` moves the source into the
   target — the source's removal piggybacks on the rename's atomicity.
   For same-path saves (no transition) the content is replaced in place
   via temp-and-rename. A trailing sweep removes any stragglers from
   prior crashes or hand-edits.

   Returns the written path. `opts` is `{:now <iso-string?> :terminal-statuses <set?>}`."
  [project-root tickets-dir id slug ticket {:keys [now terminal-statuses]}]
  (let [now*           (or now (now-iso))
        new-st         (get-in ticket [:frontmatter :status])
        existing-paths (find-all-paths project-root tickets-dir id)
        prior-st       (when-let [p (first existing-paths)]
                         (get-in (ticket/parse (slurp p)) [:frontmatter :status]))
        slug*          (or slug
                           (slug-of-filename (some-> (first existing-paths) fs/file-name))
                           "")
        target         (if (terminal? terminal-statuses new-st)
                         (archive-path project-root tickets-dir id slug*)
                         (ticket-path project-root tickets-dir id slug*))
        fm*            (stamp-timestamps (:frontmatter ticket) new-st prior-st now* terminal-statuses)
        ticket*        (assoc ticket :frontmatter fm*)
        rendered-bytes (.getBytes ^String (ticket/render ticket*) StandardCharsets/UTF_8)
        target-path    ^Path (fs/path target)
        ;; Pick a primary source: prefer one already at `target` (no
        ;; cross-directory move needed); else the first existing path.
        primary-source (or (first (filter #(= % target) existing-paths))
                           (first existing-paths))]
    (fs/create-dirs (.getParent target-path))
    (cond
      ;; Fresh ticket — atomic write straight to target.
      (nil? primary-source)
      (atomic-write! target-path rendered-bytes)

      ;; Same-location save — replace contents in place via temp-and-rename.
      (= primary-source target)
      (atomic-write! target-path rendered-bytes)

      ;; Cross-directory transition (close or reopen). Stage the new
      ;; content at the source path with an atomic content swap, then
      ;; rename source → target as a single fs op. At every observable
      ;; moment the file lives in exactly one location.
      :else
      (let [source-path ^Path (fs/path primary-source)]
        (atomic-write! source-path rendered-bytes)
        (atomic-move! source-path target-path)))
    ;; Sweep any remaining stragglers (legacy hand-edits, pre-fix crashes).
    ;; The primary source is already gone via the rename above, so this
    ;; loop only matters when `existing-paths` had more than one entry.
    (doseq [p existing-paths
            :when (and (not= p target)
                       (not= p primary-source))]
      (fs/delete-if-exists p))
    target))

(def ^:private create-new-options
  "OpenOption[] for `Files/write`. `CREATE_NEW` makes the open atomic
   and exclusive (POSIX `O_CREAT|O_EXCL`) so a racing writer at the
   same path observes `FileAlreadyExistsException`."
  (into-array OpenOption [StandardOpenOption/CREATE_NEW
                          StandardOpenOption/WRITE]))

(defn save-new!
  "Atomically create a new ticket on disk. Per attempt: call `(gen-id-fn)`
   to mint a fresh id, call `(build-fn id)` to assemble
   `{:slug s :ticket {:frontmatter ... :body ...}}`, route to live or
   archive based on the ticket's status (matching `save!`), stamp
   `:updated` and `:closed`, then write via
   `java.nio.file.Files/write` with `CREATE_NEW` — the open-side is
   atomic (POSIX `O_CREAT|O_EXCL`), so two writers racing on the same
   path see exactly one success and one `FileAlreadyExistsException`.

   On filesystem-level collision the call regenerates a fresh id and
   retries, bounded at `:max-retries` (default 10) attempts. On
   exhaustion throws `ex-info` with
   `{:kind :id-collision-exhausted :attempts <n> :last-id <id>}`.

   Caveat: `CREATE_NEW` only catches collisions at the *exact target
   path*. Cross-process races where two processes mint the same id but
   build different slugs (different titles) produce two on-disk files
   with the same id; `resolve-id` will then surface the duplicate as
   ambiguous. Same property as `save!`; not regressed by this fn.

   Returns the written path on success. `opts` is
   `{:now <iso-string?> :terminal-statuses <set?> :max-retries <int?>}`."
  [project-root tickets-dir gen-id-fn build-fn
   {:keys [now terminal-statuses max-retries]
    :or   {max-retries 10}}]
  (let [now* (or now (now-iso))]
    (loop [attempts 0
           last-id  nil]
      (if (>= attempts max-retries)
        (throw (ex-info "id collision retry exhausted"
                        {:kind     :id-collision-exhausted
                         :attempts attempts
                         :last-id  last-id}))
        (let [id        (gen-id-fn)
              {:keys [slug ticket]} (build-fn id)
              new-st    (get-in ticket [:frontmatter :status])
              target    (if (terminal? terminal-statuses new-st)
                          (archive-path project-root tickets-dir id slug)
                          (ticket-path project-root tickets-dir id slug))
              ;; Fresh ticket → no prior status to compare against.
              fm*       (stamp-timestamps (:frontmatter ticket) new-st nil now* terminal-statuses)
              ticket*   (assoc ticket :frontmatter fm*)
              rendered  (.getBytes ^String (ticket/render ticket*)
                                   StandardCharsets/UTF_8)
              target-path ^Path (fs/path target)]
          (fs/create-dirs (fs/parent target-path))
          (let [result (try
                         (Files/write target-path rendered create-new-options)
                         target
                         (catch FileAlreadyExistsException _
                           ::collision))]
            (if (= ::collision result)
              (recur (inc attempts) id)
              result)))))))

(defn load-one
  "Load and parse the ticket whose filename starts with `<id>` from either
   the live tickets directory or the archive subdirectory. Returns the
   parsed `{:frontmatter ... :body ...}` map, or nil when no matching file
   exists in either location."
  [project-root tickets-dir id]
  (when-let [path (find-existing-path project-root tickets-dir id)]
    (ticket/parse (slurp path))))

(defn load-all
  "Load and parse every ticket file across both the live tickets directory
   and its `archive/` subdirectory. Returns a sequence of
   `{:frontmatter ... :body ...}` maps, sorted by filename for stable
   order. Returns an empty seq when neither directory exists or both are
   empty."
  [project-root tickets-dir]
  (let [live    (fs/path project-root tickets-dir)
        archive (fs/path project-root tickets-dir archive-subdir)
        files   (concat (when (fs/directory? live)    (fs/glob live    "*.md"))
                        (when (fs/directory? archive) (fs/glob archive "*.md")))]
    (->> files
         (sort-by (comp str fs/file-name))
         (map (comp ticket/parse slurp str)))))

(defn- id-suffix
  "Return the post-prefix ULID portion of a ticket id (everything after
   the first `-`), or nil when the id is malformed/missing."
  [id]
  (when (string? id)
    (when-let [i (str/index-of id "-")]
      (subs id (inc i)))))

(defn- ambiguous!
  "Throw an `ex-info` reporting `input` matched multiple `tickets`. The
   message lists every candidate id so the caller can pick a longer prefix."
  [input tickets]
  (let [ids (mapv #(get-in % [:frontmatter :id]) tickets)]
    (throw (ex-info (str "ambiguous id " input ": "
                         (str/join ", " ids))
                    {:kind :ambiguous :input input :candidates ids}))))

(defn- not-found!
  "Throw an `ex-info` reporting that no ticket matched `input`. The message
   format is the AC-mandated `ticket not found: <input>`."
  [input]
  (throw (ex-info (str "ticket not found: " input)
                  {:kind :not-found :input input})))

(defn resolve-id
  "Resolve `input` (a possibly-partial ticket id) to a unique full ticket
   by scanning frontmatter `:id` across both live and archive directories.
   Layered matching:
     1. exact full-ID match wins;
     2. else a unique prefix match against the full ID (`mp-01jq8p`);
     3. else a unique prefix match against the post-prefix ULID portion
        (`01jq8p` — works without retyping the project prefix).
   Layer ordering is strict: a non-empty layer never falls through, so an
   ambiguous layer-2 match throws rather than continuing to layer 3.
   Returns the parsed ticket map `{:frontmatter ... :body ...}` on a
   unique match. Throws `ex-info` with `:kind :not-found` (message:
   `\"ticket not found: <input>\"`) when no ticket matches; with
   `:kind :ambiguous` and `:candidates [<full-ids>]` when more than one
   matches at the winning layer."
  [project-root tickets-dir input]
  (let [all   (load-all project-root tickets-dir)
        match (fn [pred]
                (vec (filter (fn [t] (pred (get-in t [:frontmatter :id]))) all)))
        exact (match #(= % input))]
    (if (= 1 (count exact))
      (first exact)
      (let [pre-full (match #(and % (str/starts-with? % input)))]
        (case (count pre-full)
          1 (first pre-full)
          0 (let [pre-suf (match #(when-let [s (id-suffix %)]
                                    (str/starts-with? s input)))]
              (case (count pre-suf)
                1 (first pre-suf)
                0 (not-found! input)
                (ambiguous! input pre-suf)))
          (ambiguous! input pre-full))))))

(defn try-resolve-id
  "Like `resolve-id` but returns the canonical full id *string* on a
   unique match, returns `input` unchanged on no match (so callers that
   tolerate broken refs — `dep`, `undep`, `unlink` `to`, `dep tree` root
   — can keep storing the user's literal input), and still throws on
   ambiguous matches."
  [project-root tickets-dir input]
  (try
    (get-in (resolve-id project-root tickets-dir input) [:frontmatter :id])
    (catch clojure.lang.ExceptionInfo e
      (if (= :not-found (:kind (ex-data e)))
        input
        (throw e)))))
