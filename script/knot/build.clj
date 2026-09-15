(ns knot.build
  "Builds release binaries: the knot uberjar appended to the upstream
  babashka executable of each platform, packed as release assets in dist/."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def all-targets
  ["linux-amd64" "linux-aarch64" "macos-amd64" "macos-aarch64" "windows-amd64"])

(defn- windows? [target] (str/starts-with? target "windows-"))

(defn upstream-asset
  "Upstream babashka archive for `target`. Linux uses the static builds so
  the binary carries no glibc floor."
  [bb-version target]
  (str "babashka-" bb-version "-" target
       (when (str/starts-with? target "linux-") "-static")
       (if (windows? target) ".zip" ".tar.gz")))

(defn upstream-url [bb-version target]
  (str "https://github.com/babashka/babashka/releases/download/v" bb-version
       "/" (upstream-asset bb-version target)))

(defn asset-name [target]
  (str "knot-" target (if (windows? target) ".zip" ".tar.gz")))

(defn exe-name [target]
  (if (windows? target) "knot.exe" "knot"))

(defn parse-sha256
  "The hash in a sha256 file: a bare hex digest, optionally followed by a
  filename."
  [s]
  (let [token (str/lower-case (first (str/split (str/trim s) #"\s+")))]
    (when-not (re-matches #"[0-9a-f]{64}" token)
      (throw (ex-info (str "Not a sha256 digest: " (pr-str s)) {:input s})))
    token))

(defn sha256sums
  "SHA256SUMS content in `sha256sum` format, from a map of filename to hash."
  [name->hash]
  (apply str (for [[n h] (sort name->hash)] (str h "  " n "\n"))))

(defn host-target [os-name os-arch]
  (let [os   (cond (str/starts-with? os-name "Linux")   "linux"
                   (str/starts-with? os-name "Mac")     "macos"
                   (str/starts-with? os-name "Windows") "windows")
        arch (case os-arch
               ("amd64" "x86_64")  "amd64"
               ("aarch64" "arm64") "aarch64"
               nil)
        target (str os "-" arch)]
    (when-not (and os arch (some #{target} all-targets))
      (throw (ex-info (str "No release target for " os-name " " os-arch)
                      {:os-name os-name :os-arch os-arch})))
    target))

(defn resolve-targets [flag host]
  (cond (= "all" flag)                  all-targets
        (= "host" flag)                 [host]
        (some #{flag} all-targets)      [flag]
        :else (throw (ex-info (str "Unknown --target " (pr-str flag)
                                   "; expected all, host or one of "
                                   (str/join ", " all-targets))
                              {:target flag}))))

(defn- sha256-file [path]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- download [url dest]
  (let [{:keys [body]} (http/get url {:as :stream})]
    (with-open [in body
                out (io/output-stream (fs/file dest))]
      (io/copy in out))))

(defn- fetch-babashka
  "Downloads and verifies the upstream babashka archive for `target` and
  returns the path of the extracted bb executable."
  [bb-version target work]
  (let [asset   (upstream-asset bb-version target)
        archive (fs/path work asset)
        url     (upstream-url bb-version target)
        bb-dir  (fs/create-dirs (fs/path work "bb"))
        bb-name (if (windows? target) "bb.exe" "bb")]
    (println "Downloading" url)
    (download url archive)
    (let [expected (parse-sha256 (:body (http/get (str url ".sha256"))))
          actual   (sha256-file archive)]
      (when-not (= expected actual)
        (throw (ex-info (str asset ": sha256 mismatch, expected " expected
                             " got " actual)
                        {:asset asset}))))
    (if (windows? target)
      (fs/unzip archive bb-dir)
      (p/shell "tar" "-xzf" (str archive) "-C" (str bb-dir) bb-name))
    (fs/path bb-dir bb-name)))

(defn- build-target
  "Writes target/release/<target>/knot[.exe] and dist/<asset>; returns the
  asset path."
  [bb-version jar target]
  (let [work  (fs/path "target" "release" target)
        _     (fs/delete-tree work)
        _     (fs/create-dirs work)
        bb    (fetch-babashka bb-version target work)
        exe   (fs/path work (exe-name target))
        asset (fs/path "dist" (asset-name target))]
    (with-open [out (io/output-stream (fs/file exe))]
      (io/copy (fs/file bb) out)
      (io/copy (fs/file jar) out))
    (if (windows? target)
      (fs/zip asset [(str exe)] {:root (str work)})
      (do (fs/set-posix-file-permissions exe "rwxr-xr-x")
          (p/shell "tar" "-czf" (str asset) "-C" (str work) (exe-name target))))
    (println "Wrote" (str asset) "from" (str exe))
    asset))

(defn -main [& args]
  (let [{:keys [target] :or {target "all"}}
        (into {} (map (fn [[k v]] [(keyword (str/replace k #"^--" "")) v])
                      (partition 2 args)))
        host       (host-target (System/getProperty "os.name")
                                (System/getProperty "os.arch"))
        targets    (resolve-targets target host)
        bb-version (str/trim (slurp ".bb-version"))
        jar        "target/knot.jar"]
    (fs/create-dirs "target")
    (fs/delete-tree (fs/path "target" "release"))
    (fs/delete-tree "dist")
    (fs/create-dirs "dist")
    (p/shell "bb" "uberjar" jar "-m" "knot.main")
    (let [assets (mapv #(build-target bb-version jar %) targets)]
      (spit "dist/SHA256SUMS"
            (sha256sums (into {} (for [a assets]
                                   [(str (fs/file-name a)) (sha256-file a)]))))
      (println "Wrote dist/SHA256SUMS"))))
