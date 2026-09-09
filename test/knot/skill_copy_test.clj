(ns knot.skill-copy-test
  "Guard the committed skill copy: `.claude/skills/knot/` must be a
   byte-for-byte mirror of `resources/knot/skill/`, the source of truth
   `knot skill install` writes out. Editing either side alone fails here."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

(def ^:private src-dir "resources/knot/skill")
(def ^:private copy-dir ".claude/skills/knot")

(defn- relative-files
  "Sorted set of every file under `dir`, as paths relative to it."
  [dir]
  (into (sorted-set)
        (comp (filter fs/regular-file?)
              (map #(str (fs/relativize dir %))))
        (fs/glob dir "**")))

(deftest skill-source-layout-test
  (testing "the source holds SKILL.md, five topic references and openai.yaml"
    (is (= (sorted-set "SKILL.md"
                       "agents/openai.yaml"
                       "references/autonomous.md"
                       "references/graph.md"
                       "references/json.md"
                       "references/lifecycle.md"
                       "references/writes.md")
           (relative-files src-dir))))
  (testing "only SKILL.md opens with YAML frontmatter"
    (doseq [f (relative-files src-dir)
            :when (not= f "SKILL.md")]
      (is (not (re-find #"^---\r?\n" (slurp (str (fs/path src-dir f)))))
          (str f " must print verbatim as a help topic, so it carries no frontmatter"))))
  (testing "SKILL.md keeps its name/description frontmatter"
    (let [head (slurp (str (fs/path src-dir "SKILL.md")))]
      (is (re-find #"(?s)\A---\nname: knot\ndescription: .+?\n---\n" head)))))

(deftest committed-copy-matches-source-test
  (testing "same file set"
    (is (= (relative-files src-dir) (relative-files copy-dir))))
  (testing "same bytes"
    (doseq [f (relative-files src-dir)]
      (is (= (slurp (str (fs/path src-dir f)))
             (slurp (str (fs/path copy-dir f))))
          (str f " differs; re-copy " src-dir " over " copy-dir)))))
