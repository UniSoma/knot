(ns knot.schema-test
  "Tests for knot.schema — generating a JSON Schema document for ticket
   frontmatter from the runtime allowed_values exposed by knot info."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.schema :as schema]
            [knot.ticket :as ticket]))

(def ^:private sample-info-data
  "A minimal info-data shape matching what `knot.cli/info-data` produces.
   Tests use this fixture instead of calling info-data directly so the
   schema generator stays a pure function of its argument."
  {:project {:knot_version "0.4.0"
             :name "Sample"
             :prefix "smp"
             :config_present true}
   :allowed_values {:statuses ["open" "in_progress" "closed"]
                    :active_status "in_progress"
                    :terminal_statuses ["closed"]
                    :types ["bug" "feature" "task" "epic" "chore"]
                    :modes ["afk" "hitl"]
                    :afk_mode "afk"
                    :priority_range {:min 0 :max 4}}})

(deftest generate-schema-shape
  (testing "returns a JSON-Schema-shaped map"
    (let [s (schema/generate-schema sample-info-data)]
      (is (string? (:$schema s)) "has a $schema declaration")
      (is (= "object" (:type s)) "top-level type is object")
      (is (map? (:properties s)) "has properties map")
      (is (vector? (:required s)) "has required vector")
      (is (every? string? (:required s)) "required entries are strings")
      (is (contains? (set (:required s)) "id")
          "id is a required property")
      (is (contains? (set (:required s)) "title")
          "title is a required property"))))

(deftest enums-from-allowed-values
  (testing "type/mode/status enums come from info-data allowed_values"
    (let [s (schema/generate-schema sample-info-data)
          props (:properties s)]
      (is (= ["bug" "feature" "task" "epic" "chore"]
             (get-in props [:type :enum]))
          "type enum matches allowed_values.types")
      (is (= ["afk" "hitl"]
             (get-in props [:mode :enum]))
          "mode enum matches allowed_values.modes")
      (is (= ["open" "in_progress" "closed"]
             (get-in props [:status :enum]))
          "status enum matches allowed_values.statuses")))

  (testing "enums change when allowed_values change (no hardcoding)"
    (let [info+ (assoc-in sample-info-data
                          [:allowed_values :types]
                          ["bug" "feature"])
          s     (schema/generate-schema info+)]
      (is (= ["bug" "feature"]
             (get-in s [:properties :type :enum]))
          "schema reflects custom types list"))))

(deftest priority-range
  (testing "priority is integer with min/max from priority_range"
    (let [s (schema/generate-schema sample-info-data)
          p (get-in s [:properties :priority])]
      (is (= "integer" (:type p)))
      (is (= 0 (:minimum p)))
      (is (= 4 (:maximum p)))))

  (testing "priority range follows info-data, not hardcoded"
    (let [info+ (assoc-in sample-info-data
                          [:allowed_values :priority_range]
                          {:min 1 :max 9})
          p (get-in (schema/generate-schema info+) [:properties :priority])]
      (is (= 1 (:minimum p)))
      (is (= 9 (:maximum p))))))

(deftest id-pattern-uses-prefix
  (testing "id pattern is derived from project.prefix, not hardcoded"
    (let [s (schema/generate-schema sample-info-data)
          pat (get-in s [:properties :id :pattern])]
      (is (string? pat) "id has a pattern string")
      (is (re-find #"smp" pat)
          "pattern mentions the configured prefix")
      (is (re-matches (re-pattern pat) "smp-01krm32fp6r9")
          "well-formed id with the configured prefix matches")
      (is (not (re-matches (re-pattern pat) "kno-01krm32fp6r9"))
          "id with the wrong prefix does not match")
      (is (not (re-matches (re-pattern pat) "smp-"))
          "id with missing suffix does not match")
      (is (not (re-matches (re-pattern pat) "smp-01krm32fp6r9X"))
          "id with extra char does not match")
      (is (not (re-matches (re-pattern pat) "smp-01krm32fp6ri"))
          "id with non-Crockford char (i) does not match")))

  (testing "prefix change produces a different pattern"
    (let [s (schema/generate-schema
             (assoc-in sample-info-data [:project :prefix] "kno"))
          pat (get-in s [:properties :id :pattern])]
      (is (re-matches (re-pattern pat) "kno-01krm32fp6r9"))
      (is (not (re-matches (re-pattern pat) "smp-01krm32fp6r9"))))))

(deftest acceptance-array-shape
  (testing "acceptance is an array of {title, done} objects"
    (let [s (schema/generate-schema sample-info-data)
          ac (get-in s [:properties :acceptance])]
      (is (= "array" (:type ac)))
      (let [item (:items ac)]
        (is (= "object" (:type item)))
        (is (= "string" (get-in item [:properties :title :type])))
        (is (= "boolean" (get-in item [:properties :done :type])))
        (is (= ["title" "done"] (vec (:required item))))))))

;; --- Minimal hand-rolled JSON-Schema-subset validator ------------------------
;; Covers only the constructs `generate-schema` actually emits: type,
;; enum, pattern, minimum/maximum, required, properties (for objects),
;; items (for arrays). Missing optional properties are not errors;
;; properties not declared in the schema pass through. Returns a vector
;; of error strings (empty = valid).

(defn- type-ok? [kind v]
  (case kind
    "string"  (string? v)
    "integer" (integer? v)
    "boolean" (boolean? v)
    "array"   (sequential? v)
    "object"  (map? v)
    true))

(declare validate-value)

(defn- validate-object [schema obj path]
  (let [required (set (:required schema))
        present  (set (map name (keys obj)))
        missing  (remove present required)]
    (concat
     (for [k missing]
       (str path ": missing required property '" k "'"))
     (mapcat
      (fn [[k v]]
        (let [kw (keyword (name k))]
          (when-let [prop-schema (get-in schema [:properties kw])]
            (validate-value prop-schema v (str path "." (name k))))))
      obj))))

(defn- validate-value [schema v path]
  (let [kind (:type schema)
        errs (cond-> []
               (and kind (not (type-ok? kind v)))
               (conj (str path ": expected " kind ", got " (pr-str v)))

               (and (:enum schema) (not (contains? (set (:enum schema)) v)))
               (conj (str path ": value " (pr-str v) " not in enum " (vec (:enum schema))))

               (and (:pattern schema) (string? v)
                    (not (re-matches (re-pattern (:pattern schema)) v)))
               (conj (str path ": " (pr-str v) " does not match pattern " (:pattern schema)))

               (and (some? (:minimum schema)) (number? v) (< v (:minimum schema)))
               (conj (str path ": " v " < minimum " (:minimum schema)))

               (and (some? (:maximum schema)) (number? v) (> v (:maximum schema)))
               (conj (str path ": " v " > maximum " (:maximum schema))))]
    (cond
      (and (= "object" kind) (map? v))
      (concat errs (validate-object schema v path))

      (and (= "array" kind) (sequential? v) (:items schema))
      (concat errs
              (mapcat (fn [i item]
                        (validate-value (:items schema) item
                                        (str path "[" i "]")))
                      (range) v))

      :else errs)))

(defn- validate
  "Validate a frontmatter map against the generated schema. Returns a
   vector of error strings; empty on success."
  [schema frontmatter]
  (vec (validate-object schema frontmatter "frontmatter")))

(deftest validator-self-check
  (testing "validator catches obvious shape violations"
    (let [s (schema/generate-schema sample-info-data)]
      (is (empty? (validate s {"id" "smp-01krm32fp6r9"
                               "title" "ok"
                               "type" "task"
                               "mode" "afk"
                               "status" "open"
                               "priority" 2
                               "acceptance" [{"title" "x" "done" false}]})))
      (is (seq (validate s {"title" "missing id"})))
      (is (seq (validate s {"id" "bad-id" "title" "x"})))
      (is (seq (validate s {"id" "smp-01krm32fp6r9" "title" "x" "type" "bogus"})))
      (is (seq (validate s {"id" "smp-01krm32fp6r9" "title" "x" "priority" 99})))
      (is (seq (validate s {"id" "smp-01krm32fp6r9" "title" "x"
                            "acceptance" [{"done" true}]}))))))

(def ^:private project-root
  (or (System/getProperty "user.dir") "."))

(defn- load-frontmatters
  "Return a seq of `{:path ... :frontmatter ...}` for every ticket file
   under `dir` (non-recursive)."
  [dir]
  (->> (fs/glob dir "*.md")
       (map (fn [p]
              {:path (str p)
               :frontmatter (-> p str slurp ticket/parse :frontmatter)}))))

(deftest every-real-ticket-validates
  (testing "every live + archive ticket frontmatter satisfies the schema"
    (let [project-info (assoc-in sample-info-data [:project :prefix] "kno")
          s            (schema/generate-schema project-info)
          live         (load-frontmatters (str (fs/path project-root ".tickets")))
          archive      (load-frontmatters (str (fs/path project-root ".tickets" "archive")))
          all          (concat live archive)
          failures     (for [{:keys [path frontmatter]} all
                             :let [errs (validate s frontmatter)]
                             :when (seq errs)]
                         {:path path :errors errs})]
      (is (seq all) "found at least one ticket to validate")
      (is (empty? failures)
          (str "tickets failed schema validation:\n"
               (str/join "\n" (for [{:keys [path errors]} failures]
                                (str "  " path "\n    "
                                     (str/join "\n    " errors)))))))))

(deftest schema-json-is-deterministic
  (testing "schema-json produces byte-identical output across calls"
    (let [a (schema/schema-json sample-info-data)
          b (schema/schema-json sample-info-data)]
      (is (= a b))
      (is (string? a))
      (is (str/ends-with? a "\n")
          "trailing newline so the file is well-formed and diff-stable")))

  (testing "key order is stable across the public surface"
    (let [out (schema/schema-json sample-info-data)
          schema-idx (str/index-of out "\"$schema\"")
          title-idx  (str/index-of out "\"title\"")
          type-idx   (str/index-of out "\"type\"")
          props-idx  (str/index-of out "\"properties\"")
          req-idx    (str/index-of out "\"required\"")]
      (is (apply < [schema-idx title-idx type-idx props-idx req-idx])
          "$schema → title → type → properties → required appear in order"))))

(deftest checked-in-schema-is-in-sync
  (testing "knot.schema.json on disk matches what generate-schema would emit"
    (let [project-info (assoc-in sample-info-data [:project :prefix] "kno")
          expected     (schema/schema-json project-info)
          path         (str (fs/path project-root "knot.schema.json"))
          actual       (slurp path)]
      (is (= expected actual)
          (str "knot.schema.json is out of sync with `knot info`. "
               "Run `bb gen:schema` and commit the result.")))))
