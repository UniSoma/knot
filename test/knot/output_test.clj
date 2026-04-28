(ns knot.output-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knot.output :as output]))

(deftest show-text-test
  (testing "renders frontmatter and body for the show command"
    (let [ticket {:frontmatter {:id "kno-01abc"
                                :status "open"
                                :type "task"
                                :priority 2}
                  :body "# Fix login\n\nDescription text.\n"}
          s (output/show-text ticket)]
      (is (str/includes? s "id: kno-01abc"))
      (is (str/includes? s "status: open"))
      (is (str/includes? s "type: task"))
      (is (str/includes? s "# Fix login"))
      (is (str/includes? s "Description text.")))))
