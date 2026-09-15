(ns knot.build-test
  (:require [clojure.test :refer [deftest is testing]]
            [knot.build :as build]))

(deftest upstream-asset-test
  (testing "linux targets use the static babashka builds"
    (is (= "babashka-1.13.222-linux-amd64-static.tar.gz"
           (build/upstream-asset "1.13.222" "linux-amd64")))
    (is (= "babashka-1.13.222-linux-aarch64-static.tar.gz"
           (build/upstream-asset "1.13.222" "linux-aarch64"))))
  (testing "macos and windows use the plain builds"
    (is (= "babashka-1.13.222-macos-aarch64.tar.gz"
           (build/upstream-asset "1.13.222" "macos-aarch64")))
    (is (= "babashka-1.13.222-windows-amd64.zip"
           (build/upstream-asset "1.13.222" "windows-amd64"))))
  (is (= "https://github.com/babashka/babashka/releases/download/v1.13.222/babashka-1.13.222-macos-amd64.tar.gz"
         (build/upstream-url "1.13.222" "macos-amd64"))))

(deftest asset-name-test
  (is (= ["knot-linux-amd64.tar.gz"
          "knot-linux-aarch64.tar.gz"
          "knot-macos-amd64.tar.gz"
          "knot-macos-aarch64.tar.gz"
          "knot-windows-amd64.zip"]
         (mapv build/asset-name build/all-targets)))
  (is (= "knot.exe" (build/exe-name "windows-amd64")))
  (is (= "knot" (build/exe-name "macos-aarch64"))))

(deftest parse-sha256-test
  (let [h "a22e7c20e42e34164a025af49f0f7bf3e4d4646b160888941c57ee98faf4b12e"]
    (is (= h (build/parse-sha256 h)) "bare hash without newline")
    (is (= h (build/parse-sha256 (str (.toUpperCase h) "  babashka.zip\n")))
        "hash followed by a filename")
    (is (thrown? clojure.lang.ExceptionInfo (build/parse-sha256 "not a hash")))))

(deftest sha256sums-test
  (is (= "bbbb  knot-a.tar.gz\naaaa  knot-b.zip\n"
         (build/sha256sums {"knot-b.zip" "aaaa" "knot-a.tar.gz" "bbbb"}))))

(deftest host-target-test
  (is (= "linux-amd64" (build/host-target "Linux" "amd64")))
  (is (= "linux-aarch64" (build/host-target "Linux" "aarch64")))
  (is (= "macos-aarch64" (build/host-target "Mac OS X" "aarch64")))
  (is (= "macos-amd64" (build/host-target "Mac OS X" "x86_64")))
  (is (= "windows-amd64" (build/host-target "Windows 11" "amd64")))
  (is (thrown? clojure.lang.ExceptionInfo (build/host-target "SunOS" "sparc"))))

(deftest resolve-targets-test
  (is (= build/all-targets (build/resolve-targets "all" "linux-amd64")))
  (is (= ["macos-aarch64"] (build/resolve-targets "host" "macos-aarch64")))
  (is (= ["windows-amd64"] (build/resolve-targets "windows-amd64" "linux-amd64")))
  (is (thrown? clojure.lang.ExceptionInfo
               (build/resolve-targets "freebsd-amd64" "linux-amd64"))))
