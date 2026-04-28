(ns knot.git
  "Read-only git integration: read `git config user.name`.
   Knot never runs git write commands."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn user-name
  "Return the trimmed value of `git config user.name`, or nil if git is not
   available, exits non-zero (e.g. no value configured), or returns blank."
  []
  (try
    (let [{:keys [exit out]} (deref (p/process ["git" "config" "user.name"]
                                               {:out :string :err :string}))]
      (when (and (zero? exit) (not (str/blank? out)))
        (str/trim out)))
    (catch Exception _ nil)))
