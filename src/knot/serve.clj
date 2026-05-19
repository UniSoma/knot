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
   missing on disk *and* on the classpath).

   `dev?` selects the cache policy: in `--dev` mode assets must never
   be cached so live edits show up immediately; in classpath mode
   assets are immutable within a binary release, so `no-cache`
   (allow browser cache but require revalidation) is enough."
  [asset asset-path content-type dev?]
  (if-let [a (asset asset-path)]
    {:status  200
     :headers {"Content-Type"  (or (:content-type a) content-type)
               "Cache-Control" (if dev? "no-store" "no-cache")}
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
   (varargs → {:status, :headers, :body}), `:port` (used by the
   origin-allowlist check), and optional `:dev?` (selects the static
   asset cache policy). Returns a ring response."
  [{:keys [request-method uri headers]}
   {:keys [asset knot-json! port dev?] :as _deps}]
  (cond
    (get static-routes uri)
    (if (= :get request-method)
      (let [{:keys [asset-path content-type]} (get static-routes uri)]
        (static-response asset asset-path content-type (boolean dev?)))
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

(def ^:private heartbeat-max-age-days
  "Heartbeats older than this are treated as stale and ignored, even if
   the recorded pid happens to be alive. Guards against pid reuse after
   a kill -9: on a long-uptime laptop, a 30-day-old heartbeat's pid is
   almost certainly an unrelated process now."
  30)

(defn- heartbeat-fresh?
  "True iff `started_at` parses and is within `heartbeat-max-age-days`
   of now. Unparseable or missing timestamps fail closed (treated as
   stale) so a corrupt heartbeat never blocks a relaunch."
  [hb]
  (boolean
   (when-let [s (:started_at hb)]
     (try
       (let [t   (Instant/parse s)
             now (Instant/now)
             max-ms (* heartbeat-max-age-days 24 60 60 1000)]
         (< (- (.toEpochMilli now) (.toEpochMilli t)) max-ms))
       (catch Exception _ false)))))

(defn already-running?
  "True iff the heartbeat names a pid that is currently alive on this
   host *and* the heartbeat is recent. We do not try to verify the pid
   is *our* `knot serve` process — pid reuse is rare on a single-user
   workstation, and the age check (see [[heartbeat-max-age-days]])
   covers the long-lived-laptop case where pid reuse becomes likely."
  [hb]
  (boolean
   (when-let [pid (:pid hb)]
     (and (.isPresent (ProcessHandle/of pid))
          (heartbeat-fresh? hb)))))

(def ^:private classpath-asset-prefix "knot/serve/public/")
(def ^:private dev-asset-prefix-rel "resources/knot/serve/public")

(defn- dev-asset-dir
  "Resolve the `--dev` asset directory against `project-root` so the
   flag works regardless of which subdirectory the user ran `knot serve`
   from. Falls back to the relative path when project-root is nil
   (defensive — `serve-cmd` already gates on `:project-found?`)."
  [project-root]
  (if project-root
    (str (fs/path project-root dev-asset-prefix-rel))
    dev-asset-prefix-rel))

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

(defn- windows?
  []
  (str/starts-with? (or (System/getProperty "os.name") "") "Windows"))

(defn- knot-shell-cmd
  "Build the argv for shelling out to `knot`. On Windows we wrap with
   `cmd /c` so the shell resolves the binary's extension via PATHEXT —
   `bbin install` produces `knot.bat`, which is invisible to Java's
   ProcessBuilder (no PATHEXT expansion) when the bare name `knot` is
   used."
  [args]
  (let [argv (concat args ["--json"])]
    (if (windows?)
      (concat ["cmd" "/c" "knot"] argv)
      (cons "knot" argv))))

(defn default-knot-json!
  "Production shell-out for /api/* routes: invoke `knot <args> --json`
   as a child process, forward stdout verbatim. Exit != 0 with empty
   stdout is reported as a 500 envelope — knot itself emits an
   ok:false envelope on data errors, so an empty stdout means the
   child died before emitting JSON. On failure the
   captured stderr is mirrored to the server's stderr and folded into
   the 500 envelope's error message so the operator can diagnose
   crashes from the browser console without tailing a log."
  [& args]
  (let [{:keys [out err exit]}
        (apply p/shell {:out :string :err :string :continue true}
               (knot-shell-cmd args))]
    (if (and (not (zero? exit)) (str/blank? out))
      (let [err* (str/trim (or err ""))]
        (when-not (str/blank? err*)
          (binding [*out* *err*]
            (println (str "knot serve: subprocess stderr: " err*))
            (flush)))
        {:status  500
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string
                   {:schema_version 1
                    :ok             false
                    :error          {:code    "shell_error"
                                     :message (str "knot exited " exit
                                                   (when-not (str/blank? err*)
                                                     (str ": " err*)))}})})
      {:status  200
       :headers {"Content-Type"  "application/json; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body    out})))

(defn start-server!
  "Boot http-kit on `:port` (0 = ephemeral), bound to 127.0.0.1, with a
   handler that delegates to `route`. http-kit is lazy-required here so
   every other knot command stays cheap. Returns `{:stop! fn :port int}` —
   `:port` is the actually-bound port (resolved from http-kit after bind
   when `:port 0` was requested).

   The deps map handed to `route` is built per-request with `:port` set
   to the bound port, so the origin allowlist always uses the real port
   even when the caller passed `:port 0` (ephemeral)."
  [{:keys [port] :as deps}]
  (require 'org.httpkit.server)
  (let [run-server  (resolve 'org.httpkit.server/run-server)
        server-port (resolve 'org.httpkit.server/server-port)
        server-stop (resolve 'org.httpkit.server/server-stop!)
        port-atom   (atom (or port 0))
        handler     (fn [req] (route req (assoc deps :port @port-atom)))
        srv         (run-server handler {:port                 (or port 0)
                                         :ip                   "127.0.0.1"
                                         :legacy-return-value? false})
        bound-port  (server-port srv)]
    (reset! port-atom bound-port)
    {:port  bound-port
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
  [argv {:keys [project-root project-found?]}]
  (when-not project-found?
    (println-err "knot serve: no project found at or above this directory")
    (println-err "  run from a directory inside a knot project, or `knot init` to create one")
    (System/exit 1))
  (let [{:keys [opts]} (cli/parse-args argv (help/derive-spec (get help/registry :serve)))
        port           (or (:port opts) default-port)
        dev?           (boolean (:dev opts))]
    (when (or (neg? port) (> port 65535))
      (println-err (str "knot serve: --port must be 0..65535; got " port))
      (System/exit 1))
    (let [hb-path  (heartbeat-path (tmpdir-path) project-root)
          existing (read-heartbeat hb-path)]
      (when (already-running? existing)
        (println-out (str "knot serve: already running for this project at "
                          "http://127.0.0.1:" (:port existing) "/"
                          " (pid " (:pid existing) ")"))
        (System/exit 0))
      (let [asset      (if dev?
                         (partial disk-asset (dev-asset-dir project-root))
                         classpath-asset)
            deps-stub  {:asset      asset
                        :knot-json! default-knot-json!
                        :port       port
                        :dev?       dev?}
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
        @(promise)))))
