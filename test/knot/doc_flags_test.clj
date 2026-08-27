(ns knot.doc-flags-test
  "Guard against doc/CLI flag drift: every `--flag` written after a
   `knot <cmd>` invocation in the agent-facing docs must be a flag that
   command actually accepts, per the help registry. Resolution is
   per-command on purpose — `knot create --title` names a real flag, but
   not on `create`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.help :as help]))

(def ^:private doc-files
  (concat (fs/glob ".claude/skills/knot" "**/*.md")
          (fs/glob "docs/agents" "*.md")
          [(fs/path "AGENTS.md") (fs/path "README.md")]))

(def ^:private global-flags
  "Accepted on every command by main's dispatcher, not per registry entry."
  #{"--help" "-h" "--version"})

(def ^:private invocation-re
  "`knot <cmd>` optionally followed by a subcommand word. The scanned tail
   ends at end of line, a `#` comment, a `|`/`;`/`&` shell operator, or a
   closing backtick — anything past those belongs to another command."
  #"knot\s+([a-z][a-z-]*)(?:\s+([a-z][a-z-]*))?([^#|;&`\n]*)")

(def ^:private flag-re
  "Long flags and single-letter aliases. Requires a letter after the
   dashes, so markdown rules (`---`) and bare `--` don't match; `=` and
   `,` end a token so `--acceptance-complete=false` yields the flag."
  #"(?<=\s)(--[a-z][a-z0-9-]*|-[a-zA-Z])(?=[\s=,]|$)")

(defn- join-continuations
  "Fold `\\`-continued lines into one so flags on the next line stay
   attached to their invocation."
  [text]
  (str/replace text #"\\\n\s*" " "))

(defn- resolve-command
  "Registry key for `cmd`, descending into `sub` when the entry lists it as
   a subcommand. Nil when `cmd` isn't a command (e.g. prose like
   `knot resolves`)."
  [cmd sub]
  (when-let [k (help/resolve-key help/registry cmd)]
    (let [sub-k (when sub (keyword (name k) sub))]
      (if (some #{sub-k} (get-in help/registry [k :subcommands]))
        sub-k
        k))))

(defn- accepted-flags [k]
  (into global-flags
        (mapcat (fn [{:keys [name alias]}]
                  (cond-> [(str "--" (clojure.core/name name))]
                    alias (conj (str "-" (clojure.core/name alias))))))
        (get-in help/registry [k :flags])))

(defn extract-invocations
  "Seq of {:cmd <registry-key> :flags [..]} for every knot invocation in
   `text`. Only text after `knot <cmd>` is scanned, so flags belonging to
   other tools on the same page never appear."
  [text]
  (for [[_ cmd sub tail] (re-seq invocation-re (join-continuations text))
        :let [k (resolve-command cmd sub)]
        :when k]
    {:cmd k :flags (map second (re-seq flag-re (str " " tail)))}))

(defn- drift
  "Vector of {:file :cmd :flag} for every documented flag its command
   rejects."
  []
  (for [f doc-files
        {:keys [cmd flags]} (extract-invocations (slurp (str f)))
        :let [ok (accepted-flags cmd)]
        flag flags
        :when (not (ok flag))]
    {:file (str f) :cmd cmd :flag flag}))

(deftest extractor-test
  (testing "flags are attributed to the command they follow"
    (is (= [{:cmd :create :flags ["--type" "-p" "--tags"]}]
           (extract-invocations "knot create \"T\" --type bug -p 1 --tags a,b"))))

  (testing "subcommands resolve to their own registry entry"
    (is (= [{:cmd :dep/tree :flags ["--full"]}]
           (extract-invocations "knot dep tree <id> --full"))))

  (testing "aliases resolve to the canonical command"
    (is (= [{:cmd :list :flags ["--json"]}]
           (extract-invocations "knot ls --json"))))

  (testing "non-knot flags on the same page are ignored"
    (is (empty? (mapcat :flags
                        (extract-invocations
                         "clj-kondo --lint src\nclj-nrepl-eval --timeout 5 --discover-ports")))))

  (testing "shell comments and table rules don't contribute flags"
    (is (= [{:cmd :check :flags ["--json"]}]
           (extract-invocations "knot check --json   # also try --bogus\n|---|---|"))))

  (testing "backslash continuations keep flags attached"
    (is (= [{:cmd :list :flags ["--json" "--mode"]}]
           (extract-invocations "knot list --json \\\n  --mode afk"))))

  (testing "=value flags yield the flag name"
    (is (= [{:cmd :list :flags ["--acceptance-complete" "--mode"]}]
           (extract-invocations "knot list --acceptance-complete=false --mode afk")))))

(deftest docs-name-only-accepted-flags-test
  (testing "every documented knot flag is accepted by the command it is written against"
    (is (empty? (drift))
        (str "flags the docs use that the CLI rejects:\n"
             (str/join "\n" (map pr-str (drift)))))))
