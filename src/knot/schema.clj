(ns knot.schema
  "Generate a JSON Schema document for ticket frontmatter from the
   runtime allowed_values exposed by `knot info`. The schema is
   editor-facing (VSCode + any YAML/JSON-schema-aware tooling)."
  (:require [cheshire.core :as json]
            [flatland.ordered.map :refer [ordered-map]]))

(def ^:private schema-uri
  "JSON Schema Draft-07. Pinned because Draft-2020-12 ordering subtly
   differs (e.g. `$defs` vs `definitions`) and editor support for 07 is
   universal. If we ever need 2020-12 features, change the URI here."
  "http://json-schema.org/draft-07/schema#")

(def ^:private id-suffix-class
  "Crockford base32 character class, lowercase (no i, l, o, u). The
   12-char body that follows `<prefix>-` is 10 timestamp chars + 2
   random chars per `knot.ticket/format-id`."
  "[0-9a-hjkmnpqrstvwxyz]")

(def ^:private id-suffix-len 12)

(defn- id-pattern
  "Build the `id` regex pattern for a given project prefix. The prefix
   is interpolated raw — `knot.config` validates `:prefix` against
   `[a-z0-9]+`, so it carries no regex metacharacters."
  [prefix]
  (str "^" prefix "-" id-suffix-class "{" id-suffix-len "}$"))

(defn generate-schema
  "Return a JSON-Schema-shaped Clojure map derived from `info-data`
   (the same map produced by `knot.cli/info-data`). Pure function — no
   I/O, no JSON serialization. Use `schema-json` to serialize."
  [info-data]
  (let [{:keys [types modes statuses priority_range]} (:allowed_values info-data)
        {p-min :min p-max :max} priority_range
        prefix (get-in info-data [:project :prefix])]
    (ordered-map
     :$schema schema-uri
     :title "Knot ticket frontmatter"
     :description
     (str "JSON Schema for the YAML frontmatter of Knot ticket files "
          "(`.tickets/*.md`). Regenerated from `knot info --json` — run "
          "`bb gen:schema` to refresh the checked-in `knot.schema.json` "
          "after changing allowed values. See knot.schema/generate-schema.")
     :type "object"
     :properties (ordered-map
                  :id       (ordered-map :type "string"
                                         :pattern (id-pattern prefix))
                  :title    (ordered-map :type "string")
                  :type     (ordered-map :type "string" :enum (vec types))
                  :mode     (ordered-map :type "string" :enum (vec modes))
                  :status   (ordered-map :type "string" :enum (vec statuses))
                  :priority (ordered-map :type "integer"
                                         :minimum p-min
                                         :maximum p-max)
                  :acceptance
                  (ordered-map
                   :type "array"
                   :items (ordered-map
                           :type "object"
                           :properties (ordered-map
                                        :title (ordered-map :type "string")
                                        :done  (ordered-map :type "boolean"))
                           :required ["title" "done"])))
     :required ["id" "title"])))

(defn schema-json
  "Serialize `(generate-schema info-data)` to a pretty-printed JSON
   string with a trailing newline. Output is deterministic given the
   same input: `generate-schema` returns ordered-maps, so key order is
   preserved through serialization."
  [info-data]
  (str (json/generate-string (generate-schema info-data) {:pretty true})
       "\n"))
