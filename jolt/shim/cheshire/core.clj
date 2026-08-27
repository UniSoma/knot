(ns cheshire.core
  "cheshire.core over clojure.data.json — jolt cannot run Jackson.
   Covers only what knot calls: parse-string and generate-string."
  (:require [clojure.data.json :as json]))

(defn parse-string
  ([s] (parse-string s nil))
  ([s key-fn]
   (when s
     (cond
       (true? key-fn) (json/read-str s :key-fn keyword)
       (fn? key-fn)   (json/read-str s :key-fn key-fn)
       :else          (json/read-str s)))))

(defn generate-string
  ([v] (generate-string v nil))
  ([v opts]
   ;; Jackson leaves `/` and non-ASCII unescaped; match it.
   (json/write-str v :escape-slash false :escape-unicode false
                   :indent (boolean (:pretty opts)))))

(def decode parse-string)
(def encode generate-string)
