;; knot serve — Web UI prototype.
;;
;; Throwaway. Read-only against `.tickets/`. Shells out to `knot ... --json`
;; for fidelity with the future VSCode/Emacs-xwidget integration story.
;; Layout: single-column "Stack" — see docs/adr/0005-knot-serve-stack-layout.md.
;;
;; Run via: bb prototype:serve
;; Listens on http://localhost:7777/.

(ns prototype.serve.server
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [org.httpkit.server :as http]))

(def port 7777)

(def public-dir
  (str (fs/path (fs/cwd) "prototype" "serve" "public")))

(defn static-file
  [filename content-type]
  (let [f (fs/path public-dir filename)]
    (if (fs/exists? f)
      {:status 200
       :headers {"Content-Type" content-type
                 "Cache-Control" "no-store"}
       :body (slurp (str f))}
      {:status 404 :body (str "not found: " filename)})))

(defn knot-json
  "Shell out to `knot <args> --json`. Returns the raw JSON envelope
  on stdout regardless of exit code — knot emits a valid `ok:false`
  envelope for data errors. Exit != 0 with empty stdout is treated
  as a 500."
  [& args]
  (let [{:keys [out exit]}
        (apply p/shell {:out :string :err :string :continue true}
               "knot" (concat args ["--json"]))]
    (if (and (not (zero? exit)) (str/blank? out))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (str "{\"schema_version\":1,\"ok\":false,"
                  "\"error\":{\"code\":\"shell_error\","
                  "\"message\":\"knot exited " exit " with no stdout\"}}")}
      {:status 200
       :headers {"Content-Type" "application/json; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body out})))

(defn route
  [{:keys [request-method uri]}]
  (cond
    (and (= :get request-method) (= "/" uri))
    (static-file "index.html" "text/html; charset=utf-8")

    (and (= :get request-method) (= "/styles.css" uri))
    (static-file "styles.css" "text/css; charset=utf-8")

    (and (= :get request-method) (= "/app.js" uri))
    (static-file "app.js" "application/javascript; charset=utf-8")

    (and (= :get request-method) (= "/api/ready" uri))
    (knot-json "ready")

    (and (= :get request-method) (= "/api/in-progress" uri))
    (knot-json "list" "--status" "in_progress")

    (and (= :get request-method) (= "/api/blocked" uri))
    (knot-json "blocked")

    (and (= :get request-method) (str/starts-with? uri "/api/show/"))
    (let [id (subs uri (count "/api/show/"))]
      (if (re-matches #"[A-Za-z0-9-]+" id)
        (knot-json "show" id)
        {:status 400 :body "bad id"}))

    :else
    {:status 404 :body "not found"}))

(defn -main
  [& _]
  (http/run-server route {:port port})
  (println (str "knot serve prototype — http://localhost:" port "/"))
  (println "  ctrl-c to stop")
  @(promise))

(-main)
