(ns knot.store-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.doc :as doc]
            [knot.store :as store]
            [knot.ticket :as ticket]))

(defmacro with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

(defn- droot
  "The default document corpus root for a sandbox project. `:docs-dir` is
   nil in these tests, so this is what `store/docs-root` resolves to."
  [tmp]
  (store/docs-root tmp ".tickets" nil))

(def ^:private terminal-statuses #{"closed"})
(def ^:private save-opts {:now "2026-04-28T12:00:00Z"
                          :terminal-statuses terminal-statuses})

(defn- mk-ticket
  "Build a minimal ticket map for tests."
  ([id status] (mk-ticket id status ""))
  ([id status body]
   {:frontmatter {:id id :status status} :body body}))

(defn- read-fm [path]
  (:frontmatter (ticket/parse (slurp path))))

(deftest ticket-path-test
  ;; ticket-path returns native-shape paths (stdout consumers expect that
  ;; — the path is round-trippable through the local shell). The expected
  ;; side builds via `(fs/path ...)` so it matches the platform separator.
  (testing "with a slug, the path is <tickets-dir>/<id>--<slug>.md"
    (is (= (str (fs/path "/p" ".tickets" "kno-01abc--my-title.md"))
           (store/ticket-path "/p" ".tickets" "kno-01abc" "my-title"))))
  (testing "with empty slug, the path is the bare <id>.md"
    (is (= (str (fs/path "/p" ".tickets" "kno-01abc.md"))
           (store/ticket-path "/p" ".tickets" "kno-01abc" "")))
    (is (= (str (fs/path "/p" ".tickets" "kno-01abc.md"))
           (store/ticket-path "/p" ".tickets" "kno-01abc" nil)))))

(deftest save-and-load-test
  (testing "save! writes a slug-suffixed file and load-one reads it back"
    (with-tmp tmp
      (let [ticket {:frontmatter {:id "kno-01abc" :title "Fix login" :status "open"}
                    :body        "Description.\n"}
            path   (store/save! tmp ".tickets" "kno-01abc" "fix-login"
                                ticket save-opts)]
        (is (fs/exists? path))
        (is (= path
               (str (fs/path tmp ".tickets" "kno-01abc--fix-login.md"))))
        (let [loaded (store/load-one tmp ".tickets" "kno-01abc")]
          (is (some? loaded))
          (is (= "kno-01abc" (get-in loaded [:frontmatter :id])))
          (is (= "Fix login" (get-in loaded [:frontmatter :title])))
          (is (= "open" (get-in loaded [:frontmatter :status])))
          (is (= "Description.\n" (:body loaded)))))))

  (testing "save! creates the tickets directory if missing"
    (with-tmp tmp
      (let [ticket {:frontmatter {:id "kno-02" :status "open"} :body ""}]
        (store/save! tmp ".tickets" "kno-02" "" ticket save-opts)
        (is (fs/directory? (fs/path tmp ".tickets")))
        (is (fs/exists? (fs/path tmp ".tickets" "kno-02.md"))))))

  (testing "load-one returns nil when no ticket matches the id"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (store/load-one tmp ".tickets" "missing-id"))))))

(deftest save-bumps-updated-test
  (testing "save! always sets :updated to (:now opts), overriding stale input"
    (with-tmp tmp
      (let [ticket {:frontmatter {:id "kno-01" :status "open"
                                  :updated "2020-01-01T00:00:00Z"}
                    :body ""}
            path (store/save! tmp ".tickets" "kno-01" "" ticket save-opts)]
        (is (= "2026-04-28T12:00:00Z" (:updated (read-fm path))))))))

(deftest save-closed-stamping-test
  (testing "save! sets :closed when transitioning into a terminal status"
    (with-tmp tmp
      (store/save! tmp ".tickets" "kno-01" "t" (mk-ticket "kno-01" "open") save-opts)
      (let [later   (assoc save-opts :now "2026-05-01T00:00:00Z")
            path    (store/save! tmp ".tickets" "kno-01" "t"
                                 (mk-ticket "kno-01" "closed") later)
            fm      (read-fm path)]
        (is (= "closed" (:status fm)))
        (is (= "2026-05-01T00:00:00Z" (:closed fm))))))

  (testing "save! does not set :closed for non-terminal statuses"
    (with-tmp tmp
      (let [path (store/save! tmp ".tickets" "kno-01" ""
                              (mk-ticket "kno-01" "open") save-opts)]
        (is (not (contains? (read-fm path) :closed))))))

  (testing "save! clears :closed when transitioning back to non-terminal"
    (with-tmp tmp
      (let [closed   {:frontmatter {:id "kno-01" :status "closed"
                                    :closed "2026-04-01T00:00:00Z"}
                      :body ""}
            _        (store/save! tmp ".tickets" "kno-01" "" closed save-opts)
            reopened (mk-ticket "kno-01" "open")
            path     (store/save! tmp ".tickets" "kno-01" "" reopened save-opts)]
        (is (not (contains? (read-fm path) :closed))))))

  (testing "save! preserves :closed when status was already that terminal"
    (with-tmp tmp
      (let [first-closed (assoc save-opts :now "2026-01-01T00:00:00Z")
            _ (store/save! tmp ".tickets" "kno-01" ""
                           (mk-ticket "kno-01" "closed") first-closed)
            ;; Re-save while still closed at a later time (e.g. body edit)
            later (assoc save-opts :now "2026-06-01T00:00:00Z")
            ;; Caller passes the prior frontmatter (with :closed) per
            ;; load → modify → save discipline.
            ticket {:frontmatter {:id "kno-01" :status "closed"
                                  :closed "2026-01-01T00:00:00Z"}
                    :body "edited"}
            path (store/save! tmp ".tickets" "kno-01" "" ticket later)]
        (is (= "2026-01-01T00:00:00Z" (:closed (read-fm path)))
            "same-terminal save should keep the original :closed timestamp"))))

  (testing "same-terminal save with :closed missing in input still stamps :closed"
    (with-tmp tmp
      (let [first-closed (assoc save-opts :now "2026-01-01T00:00:00Z")
            _ (store/save! tmp ".tickets" "kno-01" ""
                           (mk-ticket "kno-01" "closed") first-closed)
            ;; Caller passes a frontmatter that's terminal but lacks :closed.
            ;; The invariant "terminal → :closed is set" must hold.
            later (assoc save-opts :now "2026-06-01T00:00:00Z")
            path  (store/save! tmp ".tickets" "kno-01" ""
                               (mk-ticket "kno-01" "closed") later)]
        (is (= "2026-06-01T00:00:00Z" (:closed (read-fm path)))
            "missing :closed on terminal save should be stamped, not silently dropped"))))

  (testing ":closed is rendered immediately after :updated"
    (with-tmp tmp
      (let [path  (store/save! tmp ".tickets" "kno-01" ""
                               (mk-ticket "kno-01" "closed") save-opts)
            keys* (vec (keys (read-fm path)))
            upd-i (.indexOf keys* :updated)
            cls-i (.indexOf keys* :closed)]
        (is (pos? upd-i))
        (is (= cls-i (inc upd-i))
            ":closed should be inserted directly after :updated")))))

(deftest save-archive-move-test
  (testing "save! writes terminal-status tickets under <tickets-dir>/archive/"
    (with-tmp tmp
      (let [path (store/save! tmp ".tickets" "kno-01" "fix"
                              (mk-ticket "kno-01" "closed") save-opts)]
        (is (str/ends-with? path
                            (str (fs/path ".tickets" "archive" "kno-01--fix.md"))))
        (is (fs/exists? path)))))

  (testing "save! transition to terminal moves the file from live to archive"
    (with-tmp tmp
      (let [live-path    (store/save! tmp ".tickets" "kno-01" "fix"
                                      (mk-ticket "kno-01" "open") save-opts)
            archive-path (store/save! tmp ".tickets" "kno-01" "fix"
                                      (mk-ticket "kno-01" "closed") save-opts)]
        (is (fs/exists? archive-path))
        (is (not (fs/exists? live-path))
            "old live-directory file should be removed after archive move")
        (is (some #{"archive"} (map str (fs/components archive-path)))))))

  (testing "save! transition to non-terminal moves the file from archive to live"
    (with-tmp tmp
      (let [archive-path (store/save! tmp ".tickets" "kno-01" "fix"
                                      (mk-ticket "kno-01" "closed") save-opts)
            live-path    (store/save! tmp ".tickets" "kno-01" "fix"
                                      (mk-ticket "kno-01" "open") save-opts)]
        (is (fs/exists? live-path))
        (is (not (fs/exists? archive-path))
            "old archive-directory file should be removed after restore")
        (is (not (some #{"archive"} (map str (fs/components live-path))))))))

  (testing "slug suffix is preserved across archive moves"
    (with-tmp tmp
      (let [archived (store/save! tmp ".tickets" "kno-01" "my-slug"
                                  (mk-ticket "kno-01" "closed") save-opts)
            restored (store/save! tmp ".tickets" "kno-01" "my-slug"
                                  (mk-ticket "kno-01" "open") save-opts)]
        (is (str/ends-with? archived "kno-01--my-slug.md"))
        (is (str/ends-with? restored "kno-01--my-slug.md"))))))

(deftest save-self-heals-location-test
  (testing "hand-edit placing a closed ticket in live → save moves it to archive"
    (with-tmp tmp
      ;; Hand-write a closed ticket directly into the live directory.
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [stale-live (str (fs/path tmp ".tickets" "kno-01--t.md"))]
        (spit stale-live
              (ticket/render
               {:frontmatter {:id "kno-01" :status "closed"} :body ""}))
        ;; Now save through Knot; the file should be relocated to archive.
        (let [path (store/save! tmp ".tickets" "kno-01" "t"
                                (mk-ticket "kno-01" "closed") save-opts)]
          (is (some #{"archive"} (map str (fs/components path))))
          (is (fs/exists? path))
          (is (not (fs/exists? stale-live))
              "stale live-directory file should be removed by self-heal")))))

  (testing "hand-edit placing an open ticket in archive → save moves it to live"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (let [stale-archive (str (fs/path tmp ".tickets" "archive" "kno-01--t.md"))]
        (spit stale-archive
              (ticket/render
               {:frontmatter {:id "kno-01" :status "open"} :body ""}))
        (let [path (store/save! tmp ".tickets" "kno-01" "t"
                                (mk-ticket "kno-01" "open") save-opts)]
          (is (not (some #{"archive"} (map str (fs/components path)))))
          (is (fs/exists? path))
          (is (not (fs/exists? stale-archive))
              "stale archive file should be removed by self-heal")))))

  (testing "self-heal works even when the slug differs between stale file and new save"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [stale-live (str (fs/path tmp ".tickets" "kno-01--old-slug.md"))]
        (spit stale-live
              (ticket/render
               {:frontmatter {:id "kno-01" :status "closed"} :body ""}))
        (let [path (store/save! tmp ".tickets" "kno-01" "new-slug"
                                (mk-ticket "kno-01" "closed") save-opts)]
          (is (str/ends-with? path "kno-01--new-slug.md"))
          (is (not (fs/exists? stale-live))
              "old-slug stale file should be removed even when new slug differs")))))

  (testing "self-heal sweeps stale duplicates across both live and archive"
    (with-tmp tmp
      ;; Plant duplicates in BOTH locations (via hand-edit or process race).
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (let [stale-live    (str (fs/path tmp ".tickets" "kno-01--t.md"))
            stale-archive (str (fs/path tmp ".tickets" "archive" "kno-01--t.md"))
            rendered      (ticket/render
                           {:frontmatter {:id "kno-01" :status "closed"} :body ""})]
        (spit stale-live    rendered)
        (spit stale-archive rendered)
        ;; Save into archive (terminal). Both stale copies must be cleaned up:
        ;; the live one (different location), and the archive one only if
        ;; it isn't itself the target. Here the archive one IS the target, so
        ;; only the live stale should be removed.
        (let [path (store/save! tmp ".tickets" "kno-01" "t"
                                (mk-ticket "kno-01" "closed") save-opts)]
          (is (= path stale-archive))
          (is (fs/exists? path))
          (is (not (fs/exists? stale-live))
              "stale live duplicate must be removed even when target is in archive")))))

  (testing "self-heal removes stale archive copy when target is in live"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (let [stale-live    (str (fs/path tmp ".tickets" "kno-01--t.md"))
            stale-archive (str (fs/path tmp ".tickets" "archive" "kno-01--t.md"))
            rendered      (ticket/render
                           {:frontmatter {:id "kno-01" :status "open"} :body ""})]
        (spit stale-live    rendered)
        (spit stale-archive rendered)
        ;; Save to live (non-terminal). Stale archive must be removed.
        (let [path (store/save! tmp ".tickets" "kno-01" "t"
                                (mk-ticket "kno-01" "open") save-opts)]
          (is (= path stale-live))
          (is (fs/exists? path))
          (is (not (fs/exists? stale-archive))
              "stale archive duplicate must be removed when saving to live"))))))

;; --- Crash-mid-operation atomicity (kno-01kqgqafcxvv) ---
;;
;; The close/reopen round-trip moves a ticket file between the live and
;; archive directories. The invariant we care about: at every observable
;; moment (including immediately after a crash), the ticket exists in
;; *exactly one* on-disk location — never in two places, never in neither.
;;
;; We simulate a crash by replacing `fs/delete-if-exists` with a thrower.
;; That function is the last filesystem step in the legacy save! shape
;; (write target, then delete source), so injecting a throw there mimics
;; a process kill landing between the target write and the source removal.

(defn- count-locations
  "How many on-disk files exist for `id` across live + archive."
  [tmp id slug]
  (count
   (filter fs/exists?
           [(str (fs/path tmp ".tickets" (str id "--" slug ".md")))
            (str (fs/path tmp ".tickets" "archive" (str id "--" slug ".md")))])))

(deftest save-close-crash-leaves-file-in-one-place-test
  (testing "crash between target write and source removal during close (live → archive)
            must not leave the file in two places"
    (with-tmp tmp
      ;; Setup: ticket in live with status=open.
      (store/save! tmp ".tickets" "kno-01" "fix"
                   (mk-ticket "kno-01" "open") save-opts)
      (is (= 1 (count-locations tmp "kno-01" "fix")) "precondition: one file in live")
      ;; Simulate crash: the very last fs op throws.
      (with-redefs [fs/delete-if-exists (fn [& _]
                                          (throw (ex-info "simulated crash" {})))]
        (try
          (store/save! tmp ".tickets" "kno-01" "fix"
                       (mk-ticket "kno-01" "closed") save-opts)
          (catch Exception _)))
      (is (= 1 (count-locations tmp "kno-01" "fix"))
          "after a crash mid-close, file must exist in exactly one location"))))

(deftest save-reopen-crash-leaves-file-in-one-place-test
  (testing "crash between target write and source removal during reopen (archive → live)
            must not leave the file in two places"
    (with-tmp tmp
      (store/save! tmp ".tickets" "kno-01" "fix"
                   (mk-ticket "kno-01" "closed") save-opts)
      (is (= 1 (count-locations tmp "kno-01" "fix")) "precondition: one file in archive")
      (with-redefs [fs/delete-if-exists (fn [& _]
                                          (throw (ex-info "simulated crash" {})))]
        (try
          (store/save! tmp ".tickets" "kno-01" "fix"
                       (mk-ticket "kno-01" "open") save-opts)
          (catch Exception _)))
      (is (= 1 (count-locations tmp "kno-01" "fix"))
          "after a crash mid-reopen, file must exist in exactly one location"))))

(deftest load-all-test
  (testing "load-all returns every ticket file in the live tickets dir"
    (with-tmp tmp
      (let [t1 {:frontmatter {:id "kno-01" :status "open"}        :body ""}
            t2 {:frontmatter {:id "kno-02" :status "in_progress"} :body ""}
            t3 {:frontmatter {:id "kno-03" :status "closed"}      :body ""}]
        (store/save! tmp ".tickets" "kno-01" "first"  t1 save-opts)
        (store/save! tmp ".tickets" "kno-02" "second" t2 save-opts)
        (store/save! tmp ".tickets" "kno-03" ""       t3 save-opts)
        (let [loaded (store/load-all tmp ".tickets")
              ids    (set (map #(get-in % [:frontmatter :id]) loaded))]
          (is (= 3 (count loaded)))
          (is (= #{"kno-01" "kno-02" "kno-03"} ids))))))

  (testing "load-all returns an empty seq when the tickets dir is missing"
    (with-tmp tmp
      (is (empty? (store/load-all tmp ".tickets")))))

  (testing "load-all returns an empty seq when the tickets dir is empty"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (empty? (store/load-all tmp ".tickets")))))

  (testing "load-all spans both live and archive directories"
    (with-tmp tmp
      (store/save! tmp ".tickets" "kno-01" "" (mk-ticket "kno-01" "open")
                   save-opts)
      (store/save! tmp ".tickets" "kno-02" "" (mk-ticket "kno-02" "closed")
                   save-opts)
      (let [loaded (store/load-all tmp ".tickets")
            ids    (set (map #(get-in % [:frontmatter :id]) loaded))]
        (is (= #{"kno-01" "kno-02"} ids)
            "load-all should include archived tickets too"))))

  (testing "load-all works when only the archive dir exists"
    (with-tmp tmp
      (store/save! tmp ".tickets" "kno-01" "" (mk-ticket "kno-01" "closed")
                   save-opts)
      ;; remove the live dir so only archive remains
      (fs/delete-tree (fs/path tmp ".tickets"))
      (fs/create-dirs (fs/path tmp ".tickets" "archive"))
      (spit (str (fs/path tmp ".tickets" "archive" "kno-01.md"))
            (ticket/render (mk-ticket "kno-01" "closed")))
      (let [loaded (store/load-all tmp ".tickets")]
        (is (= 1 (count loaded)))
        (is (= "kno-01" (get-in (first loaded) [:frontmatter :id])))))))

(deftest load-one-finds-archived-test
  (testing "load-one resolves an id whose file lives under archive/"
    (with-tmp tmp
      (store/save! tmp ".tickets" "kno-01" "fix"
                   (mk-ticket "kno-01" "closed") save-opts)
      (let [loaded (store/load-one tmp ".tickets" "kno-01")]
        (is (some? loaded))
        (is (= "closed" (get-in loaded [:frontmatter :status])))))))

(defn- save-fixture
  "Persist a tiny set of tickets exercising every resolution layer:
   two live tickets sharing the `01abc` prefix, one archived ticket
   with a unique suffix, plus a ticket whose suffix shares a prefix
   with one of the others. Returns the tmp dir."
  [tmp]
  (store/save! tmp ".tickets" "kno-01abc111111" "alpha"
               (mk-ticket "kno-01abc111111" "open") save-opts)
  (store/save! tmp ".tickets" "kno-01abc222222" "beta"
               (mk-ticket "kno-01abc222222" "open") save-opts)
  (store/save! tmp ".tickets" "kno-99zz000000" "gamma"
               (mk-ticket "kno-99zz000000" "closed") save-opts)
  tmp)

(deftest resolve-id-test
  (testing "exact full ID match returns the ticket (layer 1)"
    (with-tmp tmp
      (save-fixture tmp)
      (let [t (store/resolve-id tmp ".tickets" "kno-01abc111111")]
        (is (= "kno-01abc111111" (get-in t [:frontmatter :id])))
        (is (= "open" (get-in t [:frontmatter :status]))))))

  (testing "prefix match against full ID resolves uniquely (layer 2)"
    (with-tmp tmp
      (save-fixture tmp)
      ;; `kno-01abc111` is a prefix only of `kno-01abc111111`
      (let [t (store/resolve-id tmp ".tickets" "kno-01abc111")]
        (is (= "kno-01abc111111" (get-in t [:frontmatter :id]))))))

  (testing "prefix match against post-prefix ULID portion (layer 3)"
    (with-tmp tmp
      (save-fixture tmp)
      ;; `01abc111` lacks the project prefix and only the suffix matches
      (let [t (store/resolve-id tmp ".tickets" "01abc111")]
        (is (= "kno-01abc111111" (get-in t [:frontmatter :id]))))))

  (testing "exact full match wins over a longer prefix-match candidate"
    (with-tmp tmp
      (save-fixture tmp)
      ;; Plant a sibling whose id starts with the exact id of another ticket
      (store/save! tmp ".tickets" "kno-01abc1111110000" "extra"
                   (mk-ticket "kno-01abc1111110000" "open") save-opts)
      (let [t (store/resolve-id tmp ".tickets" "kno-01abc111111")]
        (is (= "kno-01abc111111" (get-in t [:frontmatter :id]))
            "the exact id should win even though it is also a prefix of the sibling"))))

  (testing "prefix-of-full match wins over prefix-of-suffix match"
    (with-tmp tmp
      ;; `kno-01abcdef` and a `mp-kno-01abcdef…` style ticket would be exotic;
      ;; instead, ensure layer 2's positive match short-circuits without
      ;; consulting layer 3 by adding a layer-3-only candidate that would
      ;; otherwise also match.
      (store/save! tmp ".tickets" "kno-01zzzzzzzzzz" "alpha"
                   (mk-ticket "kno-01zzzzzzzzzz" "open") save-opts)
      (store/save! tmp ".tickets" "abc-01yyyyyyyyyy" "beta"
                   (mk-ticket "abc-01yyyyyyyyyy" "open") save-opts)
      (let [t (store/resolve-id tmp ".tickets" "kno-01")]
        ;; layer 2 picks the unique ticket whose full id starts with `kno-01`;
        ;; layer 3 would match both suffixes (`01zzzz...` and `01yyyy...`).
        (is (= "kno-01zzzzzzzzzz" (get-in t [:frontmatter :id]))))))

  (testing "ambiguous layer-2 match does NOT fall through to layer 3"
    (with-tmp tmp
      (save-fixture tmp)
      ;; Both `kno-01abc111111` and `kno-01abc222222` start with `kno-01abc`.
      ;; The resolver must error rather than continue to suffix-prefix matching.
      (let [e (try (store/resolve-id tmp ".tickets" "kno-01abc")
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "ambiguous layer-2 match should throw")
        (is (= :ambiguous (:kind (ex-data e))))
        (is (= #{"kno-01abc111111" "kno-01abc222222"}
               (set (:candidates (ex-data e))))))))

  (testing "resolution scans archive directory too"
    (with-tmp tmp
      (save-fixture tmp)
      (let [t (store/resolve-id tmp ".tickets" "99zz")]
        (is (= "kno-99zz000000" (get-in t [:frontmatter :id])))
        (is (= "closed" (get-in t [:frontmatter :status]))))))

  (testing "frontmatter :id is canonical — resolution ignores filename"
    (with-tmp tmp
      ;; Hand-write a file whose filename does not start with the id.
      (fs/create-dirs (fs/path tmp ".tickets"))
      (spit (str (fs/path tmp ".tickets" "completely-unrelated.md"))
            (ticket/render (mk-ticket "kno-canonical01" "open")))
      (let [t (store/resolve-id tmp ".tickets" "kno-canonical01")]
        (is (= "kno-canonical01" (get-in t [:frontmatter :id]))))
      (let [t (store/resolve-id tmp ".tickets" "canonical01")]
        (is (= "kno-canonical01" (get-in t [:frontmatter :id]))))))

  (testing "no match throws ex-info with kind :not-found and the input in the message"
    (with-tmp tmp
      (save-fixture tmp)
      (let [e (try (store/resolve-id tmp ".tickets" "no-such")
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= :not-found (:kind (ex-data e))))
        (is (= "no-such" (:input (ex-data e))))
        (is (str/includes? (ex-message e) "ticket not found: no-such")))))

  (testing "ambiguous match throws with the candidate IDs listed in the message"
    (with-tmp tmp
      (save-fixture tmp)
      (let [e (try (store/resolve-id tmp ".tickets" "kno-01abc")
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= :ambiguous (:kind (ex-data e))))
        (is (str/includes? (ex-message e) "kno-01abc111111"))
        (is (str/includes? (ex-message e) "kno-01abc222222"))))))

(defn- seq-gen-id-fn
  "Build a deterministic gen-id-fn from a seq of ids — pops the head on
   each call. Throws when exhausted so test fixtures with too few ids
   surface as failures rather than nil-id corruption."
  [ids]
  (let [a (atom (vec ids))]
    (fn []
      (let [[head & tail] @a]
        (when (nil? head)
          (throw (ex-info "test gen-id-fn exhausted" {:remaining 0})))
        (reset! a (vec tail))
        head))))

(defn- mk-build-fn
  "Build a build-fn that returns a fixed slug + a minimal ticket whose id
   is set from the dispatched id. Status is stamped from the test."
  [slug status]
  (fn [id]
    {:slug slug
     :ticket {:frontmatter {:id id :status status}
              :body ""}}))

(deftest save-new-happy-path-test
  (testing "save-new! writes the ticket to the live dir and returns the path"
    (with-tmp tmp
      (let [gen-id-fn (seq-gen-id-fn ["kno-fresh01"])
            build-fn  (mk-build-fn "alpha" "open")
            path      (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)]
        (is (= path (str (fs/path tmp ".tickets" "kno-fresh01--alpha.md"))))
        (is (fs/exists? path))
        (let [fm (read-fm path)]
          (is (= "kno-fresh01" (:id fm)))
          (is (= "open" (:status fm)))
          (is (= "2026-04-28T12:00:00Z" (:updated fm)))))))

  (testing "save-new! with blank slug uses bare <id>.md"
    (with-tmp tmp
      (let [gen-id-fn (seq-gen-id-fn ["kno-bare01"])
            build-fn  (mk-build-fn "" "open")
            path      (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)]
        (is (= path (str (fs/path tmp ".tickets" "kno-bare01.md")))))))

  (testing "save-new! routes terminal-status tickets to archive/"
    (with-tmp tmp
      (let [gen-id-fn (seq-gen-id-fn ["kno-done01"])
            build-fn  (mk-build-fn "fix" "closed")
            path      (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)]
        (is (str/includes? path (str (fs/path "archive" "kno-done01--fix.md"))))
        (is (fs/exists? path))
        (is (= "2026-04-28T12:00:00Z" (:closed (read-fm path)))))))

  (testing "save-new! creates parent directories as needed"
    (with-tmp tmp
      (let [gen-id-fn (seq-gen-id-fn ["kno-mkd01"])
            build-fn  (mk-build-fn "" "closed")]
        (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)
        (is (fs/directory? (fs/path tmp ".tickets" "archive")))))))

(deftest save-new-collision-retry-test
  (testing "save-new! retries past pre-existing files at the candidate path"
    (with-tmp tmp
      ;; Pre-stage a file at the path that the FIRST generated id would land on.
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [taken-path (str (fs/path tmp ".tickets" "kno-taken01--alpha.md"))]
        (spit taken-path "preexisting content — must not be overwritten")
        (let [gen-id-fn-state (atom 0)
              gen-id-fn       (let [base (seq-gen-id-fn ["kno-taken01" "kno-fresh01"])]
                                (fn []
                                  (swap! gen-id-fn-state inc)
                                  (base)))
              build-fn        (mk-build-fn "alpha" "open")
              path            (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)]
          (is (= path (str (fs/path tmp ".tickets" "kno-fresh01--alpha.md"))))
          (is (fs/exists? path))
          (is (= 2 @gen-id-fn-state)
              "gen-id-fn should be called once for the taken id, once for the fresh one")
          (is (= "preexisting content — must not be overwritten"
                 (slurp taken-path))
              "pre-existing file at the collided path must be untouched")))))

  (testing "save-new! retries past multiple consecutive collisions"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [taken-ids ["kno-t01" "kno-t02" "kno-t03"]
            _         (doseq [id taken-ids]
                        (spit (str (fs/path tmp ".tickets" (str id "--a.md"))) ""))
            gen-id-fn (seq-gen-id-fn (concat taken-ids ["kno-fresh01"]))
            build-fn  (mk-build-fn "a" "open")
            path      (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)]
        (is (str/ends-with? path "kno-fresh01--a.md"))
        (is (fs/exists? path))))))

(deftest write-new-classifies-failures-test
  ;; The classification boundary `save-new!`'s retry loop rests on: only a
  ;; path that already exists is retryable. Every other failure of the
  ;; exclusive open is a real fault and must reach the caller, or a broken
  ;; filesystem would present as an id-collision-exhausted error ten
  ;; attempts later.
  (testing "an occupied path reports a collision and leaves the file alone"
    (with-tmp tmp
      (let [p (fs/path tmp "x.md")]
        (spit (str p) "existing")
        (is (= ::store/collision (store/write-new! p (.getBytes "new" "UTF-8"))))
        (is (= "existing" (slurp (str p)))))))

  (testing "a fresh path is created and the written path returned"
    (with-tmp tmp
      (let [p (fs/path tmp "sub" "y.md")]
        (is (= (str p) (store/write-new! p (.getBytes "new" "UTF-8"))))
        (is (= "new" (slurp (str p)))))))

  (testing "any other IO failure of the open propagates rather than retrying"
    ;; A parent the process cannot write to fails the open with
    ;; AccessDeniedException — an IOException that is not a collision.
    ;;
    ;; The precondition is checked rather than assumed. uid 0 ignores the
    ;; mode bits, so under root the write below SUCCEEDS and `thrown?` would
    ;; pass vacuously — which is exactly the defect this case was written to
    ;; replace, so it must not be reintroduced here. CI containers commonly
    ;; run as root, so this is a live path, not a hypothetical one.
    (with-tmp tmp
      (let [locked (fs/path tmp "locked")]
        (fs/create-dirs locked)
        (fs/set-posix-file-permissions locked "r-xr-xr-x")
        (try
          (if (fs/writable? locked)
            (println (str "SKIP write-new-classifies-failures-test: this process can "
                          "write to a mode r-xr-xr-x directory (uid 0?), so the "
                          "non-collision IO failure cannot be provoked here."))
            (is (thrown? java.io.IOException
                         (store/write-new! (fs/path locked "z.md")
                                           (.getBytes "x" "UTF-8")))))
          (finally
            (fs/set-posix-file-permissions locked "rwxr-xr-x")))))))

(deftest save-new-exhaustion-test
  (testing "save-new! throws :id-collision-exhausted after default max-retries=10"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [taken-ids (mapv #(str "kno-tk" %) (range 10))
            _         (doseq [id taken-ids]
                        (spit (str (fs/path tmp ".tickets" (str id "--a.md"))) ""))
            gen-id-fn (seq-gen-id-fn taken-ids)
            build-fn  (mk-build-fn "a" "open")
            e         (try (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)
                           nil
                           (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "exhaustion must throw")
        (let [data (ex-data e)]
          (is (= :id-collision-exhausted (:kind data)))
          (is (= 10 (:attempts data)))
          (is (= "kno-tk9" (:last-id data))
              "last-id should be the id from the final exhausted attempt")))))

  (testing "save-new! honors a custom :max-retries"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [taken-ids ["kno-x01" "kno-x02" "kno-x03"]
            _         (doseq [id taken-ids]
                        (spit (str (fs/path tmp ".tickets" (str id "--a.md"))) ""))
            gen-id-fn (seq-gen-id-fn taken-ids)
            build-fn  (mk-build-fn "a" "open")
            opts      (assoc save-opts :max-retries 3)
            e         (try (store/save-new! tmp ".tickets" gen-id-fn build-fn opts)
                           nil
                           (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :id-collision-exhausted (:kind (ex-data e))))
        (is (= 3 (:attempts (ex-data e))))))))

(deftest save-new-atomic-create-test
  (testing "save-new! does NOT overwrite an existing file at the candidate path"
    ;; Layer B's atomicity guarantee: when CREATE_NEW catches a collision,
    ;; the prior file's content stays intact (no partial writes, no truncate).
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (let [taken-path (str (fs/path tmp ".tickets" "kno-taken01--alpha.md"))
            sentinel   "DO NOT OVERWRITE — load-bearing content"
            _          (spit taken-path sentinel)
            gen-id-fn  (seq-gen-id-fn ["kno-taken01" "kno-fresh01"])
            build-fn   (mk-build-fn "alpha" "open")]
        (store/save-new! tmp ".tickets" gen-id-fn build-fn save-opts)
        (is (= sentinel (slurp taken-path))
            "save-new! must never spit-overwrite an existing path; only CREATE_NEW")))))

(deftest try-resolve-id-test
  (testing "unique match returns the canonical full id string"
    (with-tmp tmp
      (save-fixture tmp)
      (is (= "kno-01abc111111"
             (store/try-resolve-id tmp ".tickets" "01abc111")))))

  (testing "no match returns the input unchanged (broken-ref-friendly)"
    (with-tmp tmp
      (save-fixture tmp)
      (is (= "future-id" (store/try-resolve-id tmp ".tickets" "future-id")))))

  (testing "ambiguous match still throws"
    (with-tmp tmp
      (save-fixture tmp)
      (let [e (try (store/try-resolve-id tmp ".tickets" "kno-01abc")
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= :ambiguous (:kind (ex-data e))))))))

(def ^:private planted-stamp "2026-01-01T00:00:00Z")

(defn- mkrec
  "A document record in the shape the store receives it. No I/O."
  [id ticket title type body]
  {:frontmatter {:id id :ticket ticket :title title :type type}
   :body        body})

(defn- plant-doc!
  "Write a document file straight to disk, bypassing the store's write path.
   The tests below are the failing tests FOR that write path, so a fixture
   built on `save-doc!` would assert the function under test against itself.
   `doc/filename` is shared, not re-derived — a parallel naming rule
   in the fixture would hide exactly the mismatch these tests exist to catch."
  [tmp tickets-dir rec]
  (let [{:keys [id ticket title]} (:frontmatter rec)
        dir (fs/path tmp tickets-dir "docs" ticket)
        fm  (merge {:created planted-stamp :updated planted-stamp}
                   (:frontmatter rec))
        p   (fs/path dir (doc/filename id title))]
    (fs/create-dirs dir)
    (spit (str p) (ticket/render (assoc rec :frontmatter fm)))
    (str p)))

(deftest save-doc-sibling-safety-test
  (testing "writing one document leaves every sibling byte-identical"
    (with-tmp tmp
      (let [a        (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "A" "spec" "alpha"))
            b        (plant-doc! tmp ".tickets" (mkrec "kno-d01b" "kno-01t" "B" "spec" "beta"))
            a-before (slurp a)]
        (store/save-doc! (droot tmp)
                         (mkrec "kno-d01b" "kno-01t" "B" "plan" "beta v2") {})
        (is (= a-before (slurp a)) "sibling must be untouched")
        (is (fs/exists? b))
        (is (= "beta v2" (:body (ticket/parse (slurp b)))))))))

(deftest save-doc-retitle-does-not-rename-test
  (testing "a retitle keeps the filename and creates no second file"
    (with-tmp tmp
      (let [p   (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "Old" "spec" "x"))
            _   (store/save-doc! (droot tmp)
                                 (mkrec "kno-d01a" "kno-01t" "New" "spec" "x") {})
            dir (store/owner-dir (droot tmp) "kno-01t")]
        (is (fs/exists? p))
        (is (= 1 (count (fs/glob dir "*.md"))))
        (is (= "New" (get-in (ticket/parse (slurp p)) [:frontmatter :title]))
            "the new title is stored even though the filename is unchanged")))))

(deftest save-doc-preserves-created-test
  (testing "save-doc! preserves :created and moves only :updated"
    (with-tmp tmp
      (let [p (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "T" "spec" "x"))]
        (store/save-doc! (droot tmp)
                         (mkrec "kno-d01a" "kno-01t" "T" "spec" "y")
                         {:now "2026-06-06T06:06:06Z"})
        (let [fm (:frontmatter (ticket/parse (slurp p)))]
          (is (= planted-stamp (:created fm)))
          (is (= "2026-06-06T06:06:06Z" (:updated fm))))))))

(deftest save-new-doc-is-exclusive-test
  (testing "a colliding id retries rather than overwriting an existing document"
    (with-tmp tmp
      (let [ids (atom ["kno-d01dup" "kno-d01dup" "kno-d01fresh"])
            gen #(let [v (first @ids)] (swap! ids rest) v)
            p1  (store/save-new-doc! (droot tmp) (constantly "kno-d01dup")
                                     (fn [id] {:doc (mkrec id "kno-01t" "A" "spec" "a")}) {})
            p2  (store/save-new-doc! (droot tmp) gen
                                     (fn [id] {:doc (mkrec id "kno-01t" "B" "spec" "b")}) {})]
        (is (not= p1 p2) "the second create must not land on the first's path")
        (is (str/includes? (str p2) "kno-d01fresh"))
        (is (= "a" (:body (ticket/parse (slurp p1)))) "the first document is untouched")))))

(deftest save-new-doc-detects-a-cross-owner-collision-test
  (testing "uniqueness is corpus-wide: CREATE_NEW alone cannot see this"
    (with-tmp tmp
      (let [ids (atom ["kno-d01dup" "kno-d01fresh"])
            gen #(let [v (first @ids)] (swap! ids rest) v)
            p1  (store/save-new-doc! (droot tmp) (constantly "kno-d01dup")
                                     (fn [id] {:doc (mkrec id "kno-01A" "A" "spec" "a")}) {})
            ;; different OWNER, so the target path differs and CREATE_NEW would succeed
            p2  (store/save-new-doc! (droot tmp) gen
                                     (fn [id] {:doc (mkrec id "kno-01B" "B" "spec" "b")}) {})]
        (is (str/includes? (str p2) "kno-d01fresh")
            "the duplicate id must be rejected across owner directories, not just within one")
        (is (= "a" (:body (ticket/parse (slurp p1)))))))))

(deftest save-new-doc-exhaustion-test
  (testing "save-new-doc! throws :id-collision-exhausted when every id collides"
    (with-tmp tmp
      (store/save-new-doc! (droot tmp) (constantly "kno-d01dup")
                           (fn [id] {:doc (mkrec id "kno-01t" "A" "spec" "a")}) {})
      (let [e (try (store/save-new-doc! (droot tmp) (constantly "kno-d01dup")
                                        (fn [id] {:doc (mkrec id "kno-01t" "B" "spec" "b")})
                                        {:max-retries 3})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :id-collision-exhausted (:kind (ex-data e))))
        (is (= 3 (:attempts (ex-data e))))))))

(deftest save-new-doc-stamps-both-timestamps-test
  (testing "a created document carries :created and :updated at the same instant"
    (with-tmp tmp
      (let [p  (store/save-new-doc! (droot tmp) (constantly "kno-d01a")
                                    (fn [id] {:doc (mkrec id "kno-01t" "A" "spec" "a")})
                                    {:now "2026-05-05T05:05:05Z"})
            fm (:frontmatter (ticket/parse (slurp p)))]
        (is (= "2026-05-05T05:05:05Z" (:created fm)))
        (is (= "2026-05-05T05:05:05Z" (:updated fm)))))))

(deftest save-doc-refuses-a-missing-target-test
  (testing "save-doc! never creates"
    (with-tmp tmp
      (let [e (try (store/save-doc! (droot tmp)
                                    (mkrec "kno-d01nope" "kno-01t" "T" "spec" "b") {})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "an update against a missing document must throw")
        (is (= :not-found (:kind (ex-data e))))
        (is (empty? (store/load-all-docs (droot tmp)))
            "the refused update must not have left a file behind")))))

(deftest load-docs-for-absent-and-empty-owner-dir-test
  (testing "absent and empty owner directories both mean no documents"
    (with-tmp tmp
      (is (= [] (store/load-docs-for (droot tmp) "kno-01none")))
      (fs/create-dirs (store/owner-dir (droot tmp) "kno-01empty"))
      (is (= [] (store/load-docs-for (droot tmp) "kno-01empty"))))))

(deftest load-docs-for-is-owner-scoped-test
  (testing "load-docs-for returns one owner's documents, load-all-docs the corpus"
    (with-tmp tmp
      (plant-doc! tmp ".tickets" (mkrec "kno-d01b" "kno-01A" "B" "spec" "b"))
      (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01A" "A" "spec" "a"))
      (plant-doc! tmp ".tickets" (mkrec "kno-d01c" "kno-01B" "C" "spec" "c"))
      (is (= ["kno-d01a" "kno-d01b"]
             (mapv #(get-in % [:frontmatter :id])
                   (store/load-docs-for (droot tmp) "kno-01A")))
          "ordered by filename, not by write order")
      (is (= ["kno-d01c"]
             (mapv #(get-in % [:frontmatter :id])
                   (store/load-docs-for (droot tmp) "kno-01B"))))
      (is (= 3 (count (store/load-all-docs (droot tmp)))))
      (is (= [] (store/load-all-docs (store/docs-root tmp ".tickets-empty" nil)))))))

(deftest load-all-docs-carries-path-and-owner-test
  (testing "load-all-docs annotates each document with its path and owner dir"
    (with-tmp tmp
      (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01A" "A" "spec" "a"))
      (let [d (first (store/load-all-docs (droot tmp)))]
        (is (str/ends-with? (:path d) "kno-d01a--a.md"))
        (is (= "kno-01A" (:owner-dir d)))))))

(deftest load-all-ignores-documents-test
  (testing "the ticket corpus never sees a document"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "A" "spec" "x"))
      (is (empty? (store/load-all tmp ".tickets"))))))

(deftest resolve-doc-layers-test
  (with-tmp tmp
    (plant-doc! tmp ".tickets" (mkrec "kno-d01aaa" "kno-01A" "Design" "spec" "a"))
    (plant-doc! tmp ".tickets" (mkrec "kno-d01bbb" "kno-01A" "Rollout" "plan" "b"))
    (plant-doc! tmp ".tickets" (mkrec "kno-d02ccc" "kno-01B" "Design" "spec" "c"))
    (testing "an exact id resolves"
      (is (= "kno-d01aaa"
             (get-in (store/resolve-doc (droot tmp) "kno-d01aaa")
                     [:frontmatter :id]))))
    (testing "a unique prefix resolves"
      (is (= "kno-d02ccc"
             (get-in (store/resolve-doc (droot tmp) "kno-d02")
                     [:frontmatter :id]))))
    (testing "an ambiguous prefix is refused with its candidates named"
      (let [e (try (store/resolve-doc (droot tmp) "kno-d01") nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :ambiguous (:kind (ex-data e))))
        (is (= ["kno-d01aaa" "kno-d01bbb"] (:candidates (ex-data e))))))
    (testing "a title resolves only within its owning ticket"
      (is (= "kno-d02ccc"
             (get-in (store/resolve-doc (droot tmp) "Design" "kno-01B")
                     [:frontmatter :id])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/resolve-doc (droot tmp) "Design"))
          "without an owning ticket a title is not a selector"))
    (testing "no match is refused"
      (let [e (try (store/resolve-doc (droot tmp) "kno-d99") nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :not-found (:kind (ex-data e))))))))

(deftest resolve-doc-refuses-a-duplicate-title-test
  (testing "two documents sharing a title under one ticket are ambiguous, not first-match"
    (with-tmp tmp
      (plant-doc! tmp ".tickets" (mkrec "kno-d01aaa" "kno-01A" "Design" "spec" "a"))
      (plant-doc! tmp ".tickets" (mkrec "kno-d01bbb" "kno-01A" "Design" "plan" "b"))
      (let [e (try (store/resolve-doc (droot tmp) "Design" "kno-01A") nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :ambiguous (:kind (ex-data e))))
        (is (= ["kno-d01aaa" "kno-d01bbb"] (:candidates (ex-data e))))))))

(deftest delete-doc-test
  (testing "delete-doc! unlinks one document and leaves its siblings alone"
    (with-tmp tmp
      (let [a (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "A" "spec" "a"))
            b (plant-doc! tmp ".tickets" (mkrec "kno-d01b" "kno-01t" "B" "spec" "b"))]
        (is (= a (store/delete-doc! a)))
        (is (not (fs/exists? a)))
        (is (fs/exists? b))))))

(deftest load-docs-for-annotates-path-test
  (testing "each document carries the path it was loaded from"
    (with-tmp tmp
      (let [p (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "A" "spec" "a"))]
        (is (= [p] (mapv :path (store/load-docs-for (droot tmp) "kno-01t"))))))))

(deftest save-doc-writes-the-file-it-was-given-test
  ;; The `ticket` field is authoritative and the directory is a locator, so a
  ;; replace must write the file that was found, not one recomputed
  ;; from the owning ticket. Recomputing makes a replace fail :not-found on a
  ;; misplaced document that plainly exists.
  (testing "a document filed under the wrong owner is replaced in place"
    (with-tmp tmp
      ;; Frontmatter names kno-01B; the file sits under kno-01A.
      (let [rec  (mkrec "kno-d01a" "kno-01B" "T" "spec" "one")
            dir  (fs/path tmp ".tickets" "docs" "kno-01A")
            _    (fs/create-dirs dir)
            p    (str (fs/path dir (doc/filename "kno-d01a" "T")))
            _    (spit p (ticket/render (assoc-in rec [:frontmatter :created] planted-stamp)))
            found (store/resolve-doc (droot tmp) "kno-d01a")]
        (is (= p (:path found)) "the resolver finds it where it actually is")
        (is (= p (store/save-doc! (droot tmp)
                                  (assoc found :body "two") {})))
        (is (= "two" (:body (ticket/parse (slurp p)))))
        (is (= 1 (count (store/load-all-docs (droot tmp))))
            "the replace must not have created a second file under the named owner"))))

  (testing "without a :path the owning ticket still resolves one"
    (with-tmp tmp
      (let [p (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "T" "spec" "one"))]
        (is (= p (store/save-doc! (droot tmp)
                                  (mkrec "kno-d01a" "kno-01t" "T" "spec" "two") {})))
        (is (= "two" (:body (ticket/parse (slurp p)))))))))

(deftest save-doc-validates-required-frontmatter-test
  ;; `document add` checks the type but not the title, so the store is where
  ;; the completeness invariant has to hold — it is the boundary every write
  ;; passes through.
  (testing "create refuses a document missing a required field"
    (with-tmp tmp
      (let [e (try (store/save-new-doc!
                    (droot tmp) (constantly "kno-d01a")
                    (fn [id] {:doc {:frontmatter {:id id :ticket "kno-01t" :type "spec"}
                                    :body "b"}})
                    {})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :invalid-document (:kind (ex-data e))))
        (is (= [:title] (:missing (ex-data e))))
        (is (empty? (store/load-all-docs (droot tmp)))
            "nothing is written by a refused create"))))

  (testing "a blank title is refused just as a missing one is"
    (with-tmp tmp
      (let [e (try (store/save-new-doc!
                    (droot tmp) (constantly "kno-d01a")
                    (fn [id] {:doc {:frontmatter {:id id :ticket "kno-01t"
                                                  :title "  " :type "spec"}
                                    :body "b"}})
                    {})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :invalid-document (:kind (ex-data e)))))))

  (testing "replace refuses one too, and leaves the stored document alone"
    (with-tmp tmp
      (let [p (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "T" "spec" "one"))
            e (try (store/save-doc! (droot tmp)
                                    {:frontmatter {:id "kno-d01a" :ticket "kno-01t"
                                                   :title "T"}
                                     :body "two"}
                                    {})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :invalid-document (:kind (ex-data e))))
        (is (= [:type] (:missing (ex-data e))))
        (is (= "one" (:body (ticket/parse (slurp p)))))))))

(deftest load-doc-meta-reads-frontmatter-only-test
  ;; Listing views need each document's type, and nothing else. Parsing the
  ;; whole corpus to get it would spend on every `ls` exactly what attaching
  ;; documents was meant to save.
  (testing "the frontmatter is returned and the body is not"
    (with-tmp tmp
      (let [big (apply str (repeat 200000 "x"))]
        (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "A" "spec" big))
        (let [[m] (store/load-all-docs-meta (droot tmp))]
          (is (= "kno-d01a" (get-in m [:frontmatter :id])))
          (is (= "spec" (get-in m [:frontmatter :type])))
          (is (= "kno-01t" (get-in m [:frontmatter :ticket])))
          (is (not (contains? m :body))
              "a body key would defeat the point of the reader")
          (is (str/ends-with? (:path m) "kno-d01a--a.md"))))))

  (testing "it agrees with the full reader on every frontmatter field"
    (with-tmp tmp
      (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01t" "A" "spec" "body"))
      (plant-doc! tmp ".tickets" (mkrec "kno-d01b" "kno-01u" "B" "plan" "body"))
      (is (= (mapv :frontmatter (store/load-all-docs (droot tmp)))
             (mapv :frontmatter (store/load-all-docs-meta (droot tmp)))))))

  (testing "an absent corpus is empty, not an error"
    (with-tmp tmp
      (is (= [] (store/load-all-docs-meta (droot tmp))))))

  (testing "a file with no frontmatter yields no frontmatter rather than throwing"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets" "docs" "kno-01t"))
      (spit (str (fs/path tmp ".tickets" "docs" "kno-01t" "kno-dbare--x.md"))
            "no frontmatter here\n")
      (is (= [{}] (mapv :frontmatter (store/load-all-docs-meta (droot tmp))))))))

(deftest doc-readers-agree-on-malformed-input-test
  ;; The claim "it agrees with the full reader" is only worth the inputs it
  ;; is tested against. These are the ones that diverged: the metadata
  ;; reader was the MORE permissive of the two, so a listing reported types
  ;; that no other command agreed existed.
  (testing "both readers return the same frontmatter for every shape on disk"
    (doseq [[label content]
            [["well formed"
              "---\nid: kno-d01a\nticket: kno-01t\ntype: spec\n---\n\nbody\n"]
             ["CRLF line endings"
              "---\r\nid: kno-d01a\r\nticket: kno-01t\r\ntype: spec\r\n---\r\n\r\nbody\r\n"]
             ["closing fence with no trailing newline"
              "---\nid: kno-d01a\ntype: spec\n---"]
             ["unterminated frontmatter"
              "---\nid: kno-d01a\ntype: spec\n"]
             ["no frontmatter at all"
              "just prose\n"]
             ["empty file" ""]
             ["fence-looking line inside the body"
              "---\nid: kno-d01a\ntype: spec\n---\n\nbody\n---\nmore\n"]]]
      (with-tmp tmp
        (let [dir (fs/path tmp ".tickets" "docs" "kno-01t")]
          (fs/create-dirs dir)
          (spit (str (fs/path dir "kno-d01a--x.md")) content)
          (is (= (mapv :frontmatter (store/load-all-docs (droot tmp)))
                 (mapv :frontmatter (store/load-all-docs-meta (droot tmp))))
              (str "readers disagree on: " label)))))))

(deftest load-docs-meta-for-is-owner-scoped-test
  ;; The gates' reader: one ticket's documents, types only. The body is the
  ;; large part of a document by design, so reading it on the write path to
  ;; look at one frontmatter field is the cost documents exist to avoid.
  (testing "it returns one owner's documents, frontmatter and path only"
    (with-tmp tmp
      (let [big (apply str (repeat 100000 "x"))]
        (plant-doc! tmp ".tickets" (mkrec "kno-d01b" "kno-01A" "B" "plan" big))
        (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01A" "A" "spec" big))
        (plant-doc! tmp ".tickets" (mkrec "kno-d01c" "kno-01B" "C" "spec" big))
        (let [got (store/load-docs-meta-for (droot tmp) "kno-01A")]
          (is (= ["kno-d01a" "kno-d01b"] (mapv #(get-in % [:frontmatter :id]) got))
              "owner-scoped, ordered by filename")
          (is (= ["spec" "plan"] (mapv #(get-in % [:frontmatter :type]) got)))
          (is (every? #(not (contains? % :body)) got)
              "no body key: that is the whole point of this reader")
          (is (every? :path got))))))

  (testing "it agrees with the body-carrying reader on frontmatter"
    (with-tmp tmp
      (plant-doc! tmp ".tickets" (mkrec "kno-d01a" "kno-01A" "A" "spec" "body"))
      (is (= (mapv :frontmatter (store/load-docs-for (droot tmp) "kno-01A"))
             (mapv :frontmatter (store/load-docs-meta-for (droot tmp) "kno-01A"))))))

  (testing "an absent owner directory is empty, not an error"
    (with-tmp tmp
      (is (= [] (store/load-docs-meta-for (droot tmp) "kno-01none"))))))
