(ns knot.serve-integration-test
  "End-to-end tests for `knot.serve`: boots http-kit on an ephemeral
   port, hits each /api/* route over a real socket, asserts the
   envelope is forwarded verbatim and that the origin allowlist
   rejects cross-origin requests.

   `knot-json!` is stubbed: the goal here is to prove the wire path
   (routing, middleware, http-kit) works end-to-end. Shell-out fidelity
   is exercised by the unit tests."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [knot.serve :as serve]))

(def ^:dynamic ^:private *server* nil)

(defn- canned-envelope
  "Build a stub knot-json! that records every shell call into `calls`
   and returns a deterministic envelope keyed by argv[0]."
  [calls]
  (fn [& args]
    (swap! calls conj (vec args))
    (let [data (case (first args)
                 "ready"    [{:id "kno-01ready" :title "ready-stub"}]
                 "blocked"  [{:id "kno-01blocked" :title "blocked-stub"}]
                 "list"     [{:id "kno-01ip" :title "in-progress-stub"}]
                 "show"     {:id (second args) :title "show-stub"}
                 [])]
      {:status  200
       :headers {"Content-Type"  "application/json; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body    (json/generate-string {:schema_version 1 :ok true :data data})})))

(defn- stub-asset [path]
  (case path
    "index.html" {:body "<!doctype html><html></html>"
                  :content-type "text/html; charset=utf-8"}
    nil))

(defn- start-with-stub [calls]
  (serve/start-server!
   {:asset      stub-asset
    :knot-json! (canned-envelope calls)
    :port       0}))

(defn- server-fixture [f]
  (let [calls  (atom [])
        server (start-with-stub calls)]
    (try
      (binding [*server* (assoc server :calls calls)]
        (f))
      (finally ((:stop! server))))))

(use-fixtures :each server-fixture)

(defn- url [path]
  (str "http://127.0.0.1:" (:port *server*) path))

(defn- GET
  "Issue a GET with optional headers, returning a normalized response
   map. Suppresses redirects/throws so we can assert on status codes
   directly."
  ([path] (GET path {}))
  ([path headers]
   (http/get (url path) {:headers          headers
                         :throw            false
                         :follow-redirects false})))

(deftest static-index-roundtrip
  (testing "GET / returns the stubbed index html"
    (let [r (GET "/")]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "<!doctype html>")))))

(deftest api-routes-roundtrip
  (testing "each /api/* route forwards the stub envelope verbatim"
    (let [{:keys [calls]} *server*]
      (doseq [[path argv] [["/api/ready"          ["ready"]]
                           ["/api/blocked"        ["blocked"]]
                           ["/api/in-progress"    ["list" "--status" "in_progress"]]
                           ["/api/show/kno-01abc" ["show" "kno-01abc"]]]]
        (let [r   (GET path)
              env (json/parse-string (:body r) true)]
          (is (= 200 (:status r)) (str path " status"))
          (is (true? (:ok env))   (str path " envelope ok"))
          (is (= 1 (:schema_version env)) (str path " schema_version"))
          (is (some #{argv} @calls) (str path " shell-out argv recorded")))))))

(deftest api-origin-rejection
  (testing "/api/* with a cross-origin Origin → 403, no shell-out"
    (let [{:keys [calls]} *server*]
      (reset! calls [])
      (let [r (GET "/api/ready" {"Origin" "http://evil.example"})]
        (is (= 403 (:status r)))
        (is (empty? @calls)
            "the rejection must short-circuit before knot-json! runs")))))

(deftest api-show-bad-id
  (testing "/api/show/<garbage> → 400"
    (is (= 400 (:status (GET "/api/show/has%20space")))))

  (testing "/api/show/ (empty id) → 400"
    (is (= 400 (:status (GET "/api/show/"))))))

;; ── End-to-end: spawn `knot serve` and hit it over a real socket. ──
;;
;; The unit/integration tests above use `start-server!` directly with
;; stubs. This pair of tests pins the CLI wiring: `knot serve` actually
;; routes through main, parses flags, binds, prints the URL on stdout
;; and serves over a real socket.

(def ^:private project-root
  (or (System/getProperty "user.dir") "."))

(defn- spawn-knot-serve
  "Spawn `knot serve <args>` in `cwd` using the in-tree bb classpath
   (no bbin/PATH install required). Returns the babashka.process Process
   record. Caller must call `(.destroy (:proc ret))` to shut it down."
  [cwd & args]
  (let [bb-args (concat ["bb" "-cp" (str (fs/path project-root "src"))
                         "-e"
                         (str "(require '[knot.main]) "
                              "(apply (resolve 'knot.main/-main) *command-line-args*)")
                         "--" "serve"]
                        args)]
    (p/process bb-args
               {:dir      cwd
                :in       ""
                :out      :stream
                :err      :stream
                :shutdown p/destroy})))

(defn- read-line-with-timeout
  "Read a line from `rdr` (a BufferedReader) waiting up to `ms`
   milliseconds for it to arrive. Returns the line on success, nil on
   timeout. Avoids hanging the test suite when the spawned process
   wedges before printing anything."
  [rdr ms]
  (let [fut (future (.readLine rdr))
        v   (deref fut ms ::timeout)]
    (when (= ::timeout v) (future-cancel fut))
    (when-not (= ::timeout v) v)))

(deftest spawned-server-binds-and-serves
  (testing "knot serve --port 0 --no-open binds, prints URL, serves /api/ready"
    (let [tmp     (str (fs/create-temp-dir))
          proc    (spawn-knot-serve tmp "--port" "0" "--no-open")
          out-rdr (io/reader (:out proc))]
      (try
        (let [line (read-line-with-timeout out-rdr 10000)]
          (is (some? line) "must print the URL line on stdout")
          (when line
            (let [m (re-find #"http://127\.0\.0\.1:(\d+)/" line)]
              (is (some? m) (str "URL line was: " (pr-str line)))
              (when m
                (let [port (Long/parseLong (second m))
                      url  (str "http://127.0.0.1:" port "/api/ready")
                      r    (http/get url {:throw false})
                      env  (json/parse-string (:body r) true)]
                  (is (= 200 (:status r)))
                  (is (contains? env :ok) "envelope shape present"))))))
        (finally
          (.destroy (:proc proc))
          (try (deref proc 5000 nil) (catch Exception _ nil))
          (fs/delete-tree tmp))))))
