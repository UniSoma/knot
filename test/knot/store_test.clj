(ns knot.store-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.store :as store]
            [knot.ticket :as ticket]))

(defmacro with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

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
  (testing "with a slug, the path is <tickets-dir>/<id>--<slug>.md"
    (is (= "/p/.tickets/kno-01abc--my-title.md"
           (store/ticket-path "/p" ".tickets" "kno-01abc" "my-title"))))
  (testing "with empty slug, the path is the bare <id>.md"
    (is (= "/p/.tickets/kno-01abc.md"
           (store/ticket-path "/p" ".tickets" "kno-01abc" "")))
    (is (= "/p/.tickets/kno-01abc.md"
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
        (is (str/includes? archive-path "/archive/")))))

  (testing "save! transition to non-terminal moves the file from archive to live"
    (with-tmp tmp
      (let [archive-path (store/save! tmp ".tickets" "kno-01" "fix"
                                      (mk-ticket "kno-01" "closed") save-opts)
            live-path    (store/save! tmp ".tickets" "kno-01" "fix"
                                      (mk-ticket "kno-01" "open") save-opts)]
        (is (fs/exists? live-path))
        (is (not (fs/exists? archive-path))
            "old archive-directory file should be removed after restore")
        (is (not (str/includes? live-path "/archive/"))))))

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
          (is (str/includes? path "/archive/"))
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
          (is (not (str/includes? path "/archive/")))
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
