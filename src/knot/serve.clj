(ns knot.serve
  "knot serve — read-only Web UI. Lives on loopback, shells out to
   `knot ... --json` for every /api/* request (see ADR-0007), enforces
   an origin allowlist (see ADR-0006), and renders the single-column
   Stack layout (see ADR-0005).

   http-kit is intentionally NOT required at the top of this ns — it is
   lazy-loaded inside `serve-cmd` so other knot commands stay cheap."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [knot.help :as help])
  (:import (java.lang ProcessHandle)
           (java.security MessageDigest)
           (java.time Instant)))

(defn- sha256-hex
  "Lower-hex SHA-256 of `s`. Used to derive a stable, opaque slug
   from the project-root path for the heartbeat filename."
  [^String s]
  (let [bs (.digest (MessageDigest/getInstance "SHA-256")
                    (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) bs))))

(defn origin-allowed?
  "Predicate for the /api/* origin allowlist.

   Accepts: nil (no Origin), \"null\" (sandboxed iframes / file://),
   and the two loopback hosts on the bound port. Rejects everything
   else, including https, wrong port, and other loopback IPs."
  [origin port]
  (cond
    (nil? origin)    true
    (= "null" origin) true
    :else
    (let [allowed #{(str "http://127.0.0.1:" port)
                    (str "http://localhost:" port)}]
      (contains? allowed origin))))

(def ^:private static-routes
  "Map of static URI → {:asset-path, :content-type}. The asset-path is
   what we pass to the injected `:asset` lookup; the content-type is
   what we return to the browser."
  {"/"           {:asset-path "index.html" :content-type "text/html; charset=utf-8"}
   "/app.js"     {:asset-path "app.js"     :content-type "application/javascript; charset=utf-8"}
   "/styles.css" {:asset-path "styles.css" :content-type "text/css; charset=utf-8"}})

(defn- static-response
  "Resolve a static URI via the injected asset lookup. Returns a
   ring-style response. nil from the asset lookup → 404 (the file is
   missing on disk *and* on the classpath)."
  [asset asset-path content-type]
  (if-let [a (asset asset-path)]
    {:status  200
     :headers {"Content-Type" (or (:content-type a) content-type)
               "Cache-Control" "no-store"}
     :body    (:body a)}
    {:status 404 :body (str "not found: " asset-path)}))

(def ^:private api-show-prefix "/api/show/")

(defn- api-dispatch
  "Resolve a /api/* URI to the argv we'd pass to `knot-json!`, or to
   a literal response (e.g. 400 on a bad id). Returns either
   `[:argv [...]]` or `[:response {...}]`. Returns nil if the URI is
   not a known /api/* path."
  [uri]
  (cond
    (= "/api/ready"       uri) [:argv ["ready"]]
    (= "/api/in-progress" uri) [:argv ["list" "--status" "in_progress"]]
    (= "/api/blocked"     uri) [:argv ["blocked"]]
    (str/starts-with? uri api-show-prefix)
    (let [id (subs uri (count api-show-prefix))]
      (if (re-matches #"[A-Za-z0-9-]+" id)
        [:argv ["show" id]]
        [:response {:status 400 :body "bad id"}]))))

(defn route
  "Pure routing function. Takes a ring-style request and a `deps` map
   with `:asset` (path → {:body, :content-type}), `:knot-json!`
   (varargs → {:status, :headers, :body}), and `:port` (used by the
   origin-allowlist check). Returns a ring response."
  [{:keys [request-method uri headers]} {:keys [asset knot-json! port] :as _deps}]
  (cond
    (get static-routes uri)
    (if (= :get request-method)
      (let [{:keys [asset-path content-type]} (get static-routes uri)]
        (static-response asset asset-path content-type))
      {:status 405 :body "method not allowed"})

    (str/starts-with? uri "/api/")
    (cond
      (not (origin-allowed? (get headers "origin") port))
      {:status 403 :body "forbidden"}

      (not= :get request-method)
      {:status 405 :body "method not allowed"}

      :else
      (if-let [[tag payload] (api-dispatch uri)]
        (case tag
          :argv     (apply knot-json! payload)
          :response payload)
        {:status 404 :body "not found"}))

    :else
    {:status 404 :body "not found"}))

(defn heartbeat-path
  "Path to the per-project heartbeat file under `tmpdir`. Deterministic
   in (tmpdir, project-root) so a second `knot serve` invocation in the
   same project can detect 'already running' without inventing a daemon
   or a global registry. The slug is sha256(project-root)[:12] —
   collision-free in practice; short enough to keep the filename
   readable."
  [tmpdir project-root]
  (str (fs/path tmpdir
                (str "knot-serve-"
                     (subs (sha256-hex project-root) 0 12)
                     ".json"))))

(defn write-heartbeat!
  "Serialize `m` as JSON to `path`. The map shape is documented at the
   call site; this fn is shape-agnostic."
  [path m]
  (spit path (json/generate-string m)))

(defn read-heartbeat
  "Read a heartbeat file as JSON with keyword keys. Returns nil if the
   file is missing or the contents do not parse — we treat a corrupt
   heartbeat as 'no heartbeat present' rather than crashing the new
   process; the new process will overwrite it on start."
  [path]
  (when (fs/exists? path)
    (try
      (json/parse-string (slurp path) true)
      (catch Exception _ nil))))

(defn already-running?
  "True iff the heartbeat names a pid that is currently alive on this
   host. We do not try to verify the pid is *our* `knot serve`
   process — pid reuse is rare enough on a single-user workstation,
   and a wrong-process false positive only delays a second launch."
  [hb]
  (boolean
   (when-let [pid (:pid hb)]
     (.isPresent (ProcessHandle/of pid)))))

(def ^:private classpath-asset-prefix "knot/serve/public/")
(def ^:private dev-asset-prefix "resources/knot/serve/public")

(defn classpath-asset
  "Read a static asset from the classpath (resources/knot/serve/public/).
   Returns `{:body <string>}` or nil. Production path."
  [path]
  (when-let [u (io/resource (str classpath-asset-prefix path))]
    {:body (slurp u)}))

(defn disk-asset
  "Read a static asset from `public-dir` on disk. Used by `--dev` for
   live-edit iteration on the front-end."
  [public-dir path]
  (let [f (fs/path public-dir path)]
    (when (fs/exists? f)
      {:body (slurp (str f))})))

(defn default-knot-json!
  "Production shell-out for /api/* routes: invoke `knot <args> --json`
   as a child process, forward stdout verbatim. Exit != 0 with empty
   stdout is reported as a 500 envelope (parity with the prototype —
   knot itself emits an ok:false envelope on data errors, so an empty
   stdout means the child died before emitting JSON)."
  [& args]
  (let [{:keys [out exit]}
        (apply p/shell {:out :string :err :string :continue true}
               "knot" (concat args ["--json"]))]
    (if (and (not (zero? exit)) (str/blank? out))
      {:status  500
       :headers {"Content-Type" "application/json"}
       :body    (str "{\"schema_version\":1,\"ok\":false,"
                     "\"error\":{\"code\":\"shell_error\","
                     "\"message\":\"knot exited " exit
                     " with no stdout\"}}")}
      {:status  200
       :headers {"Content-Type"  "application/json; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body    out})))

(defn start-server!
  "Boot http-kit on `:port` (0 = ephemeral) with a handler that delegates
   to `route`. http-kit is lazy-required here so every other knot
   command stays cheap. Returns `{:stop! fn :port int}` — `:port` is
   the actually-bound port (resolved from the http-kit stopper's meta
   when `:port 0` was requested)."
  [{:keys [port] :as deps}]
  (require 'org.httpkit.server)
  (let [run-server  (resolve 'org.httpkit.server/run-server)
        server-port (resolve 'org.httpkit.server/server-port)
        server-stop (resolve 'org.httpkit.server/server-stop!)
        handler     (fn [req] (route req deps))
        srv         (run-server handler {:port (or port 0)
                                         :legacy-return-value? false})]
    {:port  (server-port srv)
     :stop! (fn [] (server-stop srv))}))

(def ^:private default-port 7777)

(defn- resolve-open?
  "Decide whether to open the system browser. Explicit --no-open wins;
   then explicit --open; otherwise default to opening when stdout is a
   tty (interactive launch) and not when piped (embedded launch)."
  [opts]
  (cond
    (:no-open opts) false
    (:open opts)    true
    :else           (boolean (System/console))))

(defn- open-browser!
  "Best-effort URL open via the platform's default opener. Silent on
   failure: a missing browser must never crash a running server."
  [url]
  (let [os  (or (System/getProperty "os.name") "")
        cmd (cond
              (str/starts-with? os "Mac")
              ["open" url]
              (or (str/includes? os "Windows") (str/includes? os "windows"))
              ["cmd" "/c" "start" "" url]
              :else
              ["xdg-open" url])]
    (try
      (p/process cmd {:in nil :out nil :err nil})
      (catch Exception _ nil))))

(defn- tmpdir-path []
  (or (System/getenv "TMPDIR")
      (System/getProperty "java.io.tmpdir")
      "/tmp"))

(defn- println-out [s]
  (println s)
  (flush))

(defn- println-err [s]
  (binding [*out* *err*] (println s) (flush)))

(defn serve-cmd
  "CLI entry for `knot serve`. Parses argv, runs the per-project
   heartbeat check, builds the deps map, starts http-kit, prints the
   URL, optionally opens the browser, and blocks until ctrl-c. The
   heartbeat file is removed via a JVM shutdown hook so a SIGINT does
   not leave stale state behind. Returns nil (or exits non-zero via
   `System/exit` on a startup failure)."
  [argv {:keys [project-root]}]
  (let [{:keys [opts]} (cli/parse-args argv (help/derive-spec (get help/registry :serve)))
        port           (or (:port opts) default-port)
        dev?           (boolean (:dev opts))
        hb-path        (heartbeat-path (tmpdir-path) project-root)
        existing       (read-heartbeat hb-path)]
    (when (already-running? existing)
      (println-out (str "knot serve: already running for this project at "
                        "http://127.0.0.1:" (:port existing) "/"
                        " (pid " (:pid existing) ")"))
      (System/exit 0))
    (let [asset      (if dev?
                       (partial disk-asset dev-asset-prefix)
                       classpath-asset)
          deps-stub  {:asset asset :knot-json! default-knot-json! :port port}
          {bound-port :port stop! :stop!} (start-server! deps-stub)
          url        (str "http://127.0.0.1:" bound-port "/")
          hb         {:pid          (.pid (ProcessHandle/current))
                      :port         bound-port
                      :started_at   (str (Instant/now))
                      :project_root project-root}]
      (write-heartbeat! hb-path hb)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable
                         (fn []
                           (try (stop!) (catch Exception _ nil))
                           (try (fs/delete-if-exists hb-path)
                                (catch Exception _ nil)))))
      (println-out (str "knot serve — " url))
      (println-err "  ctrl-c to stop")
      (when (resolve-open? opts)
        (open-browser! url))
      @(promise))))
