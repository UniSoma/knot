(ns knot.git-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.git :as git]))

(deftest user-name-test
  (testing "returns nil or a non-blank string; never throws"
    (let [v (git/user-name)]
      (is (or (nil? v) (and (string? v) (not (str/blank? v))))))))
