(ns knot.version-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.version :as version]))

(deftest version-constant-test
  (testing "knot.version/version is a non-blank string"
    (is (string? version/version))
    (is (not (str/blank? version/version))))

  (testing "knot.version/version matches semver X.Y.Z"
    (is (re-matches #"\d+\.\d+\.\d+" version/version))))
