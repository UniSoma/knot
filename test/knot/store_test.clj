(ns knot.store-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [knot.store :as store]))

(defmacro with-tmp [bind & body]
  `(let [tmp# (str (fs/create-temp-dir))
         ~bind tmp#]
     (try ~@body
          (finally (fs/delete-tree tmp#)))))

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
      (let [ticket {:frontmatter {:id "kno-01abc" :status "open"}
                    :body        "# Fix login\n\nDescription.\n"}
            path   (store/save! tmp ".tickets" "kno-01abc" "fix-login" ticket)]
        (is (fs/exists? path))
        (is (= path
               (str (fs/path tmp ".tickets" "kno-01abc--fix-login.md"))))
        (let [loaded (store/load-one tmp ".tickets" "kno-01abc")]
          (is (some? loaded))
          (is (= "kno-01abc" (get-in loaded [:frontmatter :id])))
          (is (= "open" (get-in loaded [:frontmatter :status])))
          (is (= "# Fix login\n\nDescription.\n" (:body loaded)))))))

  (testing "save! creates the tickets directory if missing"
    (with-tmp tmp
      (let [ticket {:frontmatter {:id "kno-02"} :body ""}]
        (store/save! tmp ".tickets" "kno-02" "" ticket)
        (is (fs/directory? (fs/path tmp ".tickets")))
        (is (fs/exists? (fs/path tmp ".tickets" "kno-02.md"))))))

  (testing "load-one returns nil when no ticket matches the id"
    (with-tmp tmp
      (fs/create-dirs (fs/path tmp ".tickets"))
      (is (nil? (store/load-one tmp ".tickets" "missing-id"))))))

(deftest load-all-test
  (testing "load-all returns every ticket file in the live tickets dir"
    (with-tmp tmp
      (let [t1 {:frontmatter {:id "kno-01" :status "open"}        :body ""}
            t2 {:frontmatter {:id "kno-02" :status "in_progress"} :body ""}
            t3 {:frontmatter {:id "kno-03" :status "closed"}      :body ""}]
        (store/save! tmp ".tickets" "kno-01" "first"  t1)
        (store/save! tmp ".tickets" "kno-02" "second" t2)
        (store/save! tmp ".tickets" "kno-03" ""       t3)
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
      (is (empty? (store/load-all tmp ".tickets"))))))
