(ns knot.serve-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.serve :as serve]))

(deftest origin-allowed?-test
  (testing "loopback and absent origins are accepted"
    (is (true? (serve/origin-allowed? nil 7777))
        "no Origin header (curl, same-origin) is accepted")
    (is (true? (serve/origin-allowed? "null" 7777))
        "Origin: null (sandboxed iframe, file://) is accepted")
    (is (true? (serve/origin-allowed? "http://127.0.0.1:7777" 7777)))
    (is (true? (serve/origin-allowed? "http://localhost:7777" 7777))))

  (testing "off-host and off-port origins are rejected"
    (is (false? (serve/origin-allowed? "http://evil.example" 7777)))
    (is (false? (serve/origin-allowed? "https://localhost:7777" 7777))
        "https scheme is rejected for plain-http loopback server")
    (is (false? (serve/origin-allowed? "http://127.0.0.1:7778" 7777))
        "right host, wrong port")
    (is (false? (serve/origin-allowed? "http://localhost" 7777))
        "missing port — same-origin policy still requires explicit match")
    (is (false? (serve/origin-allowed? "http://127.0.0.2:7777" 7777))
        "127.0.0.2 is loopback but not in the allowlist")))

(deftest heartbeat-path-test
  (testing "heartbeat-path is deterministic for a given (tmpdir, project-root)"
    (let [tmp  (str (fs/create-temp-dir))
          root "/home/alice/projects/foo"
          p1   (serve/heartbeat-path tmp root)
          p2   (serve/heartbeat-path tmp root)]
      (try
        (is (= p1 p2))
        (is (str/starts-with? p1 (str tmp "/")) "lives under TMPDIR")
        (is (str/starts-with? (fs/file-name p1) "knot-serve-")
            "filename prefix is knot-serve-")
        (is (str/ends-with? (fs/file-name p1) ".json")
            "extension is .json")
        (let [suffix (subs (fs/file-name p1)
                           (count "knot-serve-")
                           (- (count (fs/file-name p1)) (count ".json")))]
          (is (= 12 (count suffix))
              "the project-root hash slug is 12 chars")
          (is (re-matches #"[0-9a-f]{12}" suffix)
              "the slug is lower-hex (sha256 prefix)"))
        (finally (fs/delete-tree tmp)))))

  (testing "different project roots produce different heartbeats"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (is (not= (serve/heartbeat-path tmp "/a")
                  (serve/heartbeat-path tmp "/b")))
        (finally (fs/delete-tree tmp))))))

(def ^:private static-deps
  "Minimal deps map for `route`: an asset lookup that returns canned
   bodies for known paths, and a knot-json! stub that should not be
   invoked by static-route tests (any call asserts loud)."
  {:asset      (fn [p]
                 (case p
                   "index.html" {:body "<!doctype html>" :content-type "text/html; charset=utf-8"}
                   "app.js"     {:body "/*js*/"          :content-type "application/javascript; charset=utf-8"}
                   "styles.css" {:body "/*css*/"         :content-type "text/css; charset=utf-8"}
                   nil))
   :knot-json! (fn [& _] (throw (ex-info "knot-json! called from static route" {})))
   :port       7777})

(deftest route-static-test
  (testing "GET / returns the index html via asset lookup"
    (let [r (serve/route {:request-method :get :uri "/" :headers {}} static-deps)]
      (is (= 200 (:status r)))
      (is (= "text/html; charset=utf-8" (get-in r [:headers "Content-Type"])))
      (is (= "<!doctype html>" (:body r)))))

  (testing "GET /app.js and /styles.css are served"
    (is (= 200 (:status (serve/route {:request-method :get :uri "/app.js"     :headers {}} static-deps))))
    (is (= 200 (:status (serve/route {:request-method :get :uri "/styles.css" :headers {}} static-deps)))))

  (testing "unknown path → 404"
    (is (= 404 (:status (serve/route {:request-method :get :uri "/nope" :headers {}} static-deps)))))

  (testing "non-GET on a known static path → 405"
    (is (= 405 (:status (serve/route {:request-method :post :uri "/" :headers {}} static-deps))))))

(defn- recording-knot-json!
  "Build a `knot-json!` stub that records every call's argv into `calls`
   (an atom holding a vector) and returns the same canned envelope
   response for every invocation."
  [calls]
  (fn [& args]
    (swap! calls conj (vec args))
    {:status  200
     :headers {"Content-Type" "application/json; charset=utf-8"
               "Cache-Control" "no-store"}
     :body    "{\"schema_version\":1,\"ok\":true,\"data\":[]}"}))

(defn- api-deps [calls]
  {:asset      (fn [_] nil)
   :knot-json! (recording-knot-json! calls)
   :port       7777})

(deftest route-api-dispatch-test
  (testing "/api/ready dispatches to `knot ready`"
    (let [calls (atom [])
          r     (serve/route {:request-method :get :uri "/api/ready" :headers {}}
                             (api-deps calls))]
      (is (= 200 (:status r)))
      (is (= [["ready"]] @calls))
      (is (= "{\"schema_version\":1,\"ok\":true,\"data\":[]}" (:body r)))
      (is (= "application/json; charset=utf-8" (get-in r [:headers "Content-Type"])))))

  (testing "/api/in-progress dispatches to `knot list --status in_progress`"
    (let [calls (atom [])]
      (serve/route {:request-method :get :uri "/api/in-progress" :headers {}}
                   (api-deps calls))
      (is (= [["list" "--status" "in_progress"]] @calls))))

  (testing "/api/blocked dispatches to `knot blocked`"
    (let [calls (atom [])]
      (serve/route {:request-method :get :uri "/api/blocked" :headers {}}
                   (api-deps calls))
      (is (= [["blocked"]] @calls))))

  (testing "/api/show/:id dispatches to `knot show <id>`"
    (let [calls (atom [])]
      (serve/route {:request-method :get :uri "/api/show/kno-01abc" :headers {}}
                   (api-deps calls))
      (is (= [["show" "kno-01abc"]] @calls))))

  (testing "non-GET on /api/* → 405 (no shell-out)"
    (let [calls (atom [])
          r     (serve/route {:request-method :post :uri "/api/ready" :headers {}}
                             (api-deps calls))]
      (is (= 405 (:status r)))
      (is (empty? @calls) "knot-json! must not be invoked for non-GET"))))

(deftest route-origin-allowlist-test
  (testing "missing Origin → allowed (curl, same-origin GET)"
    (let [calls (atom [])
          r     (serve/route {:request-method :get :uri "/api/ready" :headers {}}
                             (api-deps calls))]
      (is (= 200 (:status r)))
      (is (= 1 (count @calls)))))

  (testing "loopback Origin on bound port → allowed"
    (let [calls (atom [])
          r     (serve/route {:request-method :get :uri "/api/ready"
                              :headers        {"origin" "http://127.0.0.1:7777"}}
                             (api-deps calls))]
      (is (= 200 (:status r)))))

  (testing "off-host Origin → 403 (no shell-out)"
    (let [calls (atom [])
          r     (serve/route {:request-method :get :uri "/api/ready"
                              :headers        {"origin" "http://evil.example"}}
                             (api-deps calls))]
      (is (= 403 (:status r)))
      (is (empty? @calls) "rejection must short-circuit the shell-out")))

  (testing "off-port Origin → 403"
    (let [calls (atom [])]
      (is (= 403 (:status (serve/route {:request-method :get :uri "/api/ready"
                                        :headers        {"origin" "http://127.0.0.1:9999"}}
                                       (api-deps calls)))))))

  (testing "static routes are NOT origin-gated"
    (let [r (serve/route {:request-method :get :uri "/"
                          :headers        {"origin" "http://evil.example"}}
                         static-deps)]
      (is (= 200 (:status r))))))

(deftest route-show-id-validation-test
  (testing "valid id shape → dispatches to knot show"
    (let [calls (atom [])]
      (is (= 200 (:status (serve/route {:request-method :get
                                        :uri            "/api/show/kno-01abcDEF"
                                        :headers        {}}
                                       (api-deps calls)))))
      (is (= [["show" "kno-01abcDEF"]] @calls))))

  (testing "id containing path traversal → 400, no shell-out"
    (let [calls (atom [])
          r     (serve/route {:request-method :get
                              :uri            "/api/show/..%2Fetc%2Fpasswd"
                              :headers        {}}
                             (api-deps calls))]
      (is (= 400 (:status r)))
      (is (empty? @calls))))

  (testing "id containing slash → 400, no shell-out"
    (let [calls (atom [])
          r     (serve/route {:request-method :get
                              :uri            "/api/show/kno-01abc/extra"
                              :headers        {}}
                             (api-deps calls))]
      (is (= 400 (:status r)))
      (is (empty? @calls))))

  (testing "empty id → 400"
    (let [calls (atom [])]
      (is (= 400 (:status (serve/route {:request-method :get
                                        :uri            "/api/show/"
                                        :headers        {}}
                                       (api-deps calls))))))))

(deftest heartbeat-roundtrip-test
  (testing "write-heartbeat! then read-heartbeat returns the same map"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (let [path (str (fs/path tmp "hb.json"))
              hb   {:pid 12345 :port 7777 :started_at "2026-05-18T00:00:00Z"
                    :project_root "/home/alice/proj"}]
          (serve/write-heartbeat! path hb)
          (is (fs/exists? path) "heartbeat file written")
          (is (= hb (serve/read-heartbeat path))
              "round-trip equality (keys as keywords)")
          (let [raw (json/parse-string (slurp path))]
            (is (= 12345 (get raw "pid")) "stored as JSON, not EDN")))
        (finally (fs/delete-tree tmp)))))

  (testing "read-heartbeat returns nil when the file is missing"
    (is (nil? (serve/read-heartbeat "/no/such/path/heartbeat.json"))))

  (testing "read-heartbeat returns nil on a malformed file"
    (let [tmp (str (fs/create-temp-dir))]
      (try
        (let [path (str (fs/path tmp "hb.json"))]
          (spit path "{not json")
          (is (nil? (serve/read-heartbeat path))))
        (finally (fs/delete-tree tmp))))))

(deftest already-running?-test
  (testing "live pid → true"
    (let [my-pid (.pid (java.lang.ProcessHandle/current))]
      (is (true? (serve/already-running? {:pid my-pid})))))

  (testing "non-existent pid → false"
    (is (false? (serve/already-running? {:pid 99999999}))))

  (testing "missing :pid key → false"
    (is (false? (serve/already-running? {})))
    (is (false? (serve/already-running? nil)))))
