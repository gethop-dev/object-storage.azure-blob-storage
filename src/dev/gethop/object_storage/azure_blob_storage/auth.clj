;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns dev.gethop.object-storage.azure-blob-storage.auth
  (:require [clojure.string :as str]
            [dev.gethop.object-storage.azure-blob-storage.util :as util]))

(defn- build-canonicalized-header-string
  [headers]
  (->> headers
       (keys)
       (map (comp str/lower-case name))
       (filter #(str/starts-with? % "x-ms-"))
       (sort)
       ;; TODO Replace any linear whitespace
       (map #(str % ":" (get headers (keyword %))))
       (str/join "\n")))

(defn- build-canonicalized-resource-string
  [account container object]
  (->> [(:name account)
        (:name container)
        (:id object)]
       (filter some?)
       (map util/url-encode-str)
       (reduce #(str %1 "/" %2) "")))

(defn- build-canonicalized-params-string
  [params]
  (->> params
       (keys)
       (map (comp str/lower-case name))
       (sort)
       (map #(str % ":" (get params (keyword %))))
       (str/join "\n")))

(defn build-canonicalized-string-to-sign
  [method params headers account container object]
  (->>
   [(str/upper-case (name method))
    (get headers :Content-Encoding)
    (get headers :Content-Language)
    (when (not= 0 (get headers :Content-Length))
      (get headers :Content-Length))
    (get headers :Content-MD5)
    (get headers :Content-Type)
    (get headers :Date)
    (get headers :If-Modified-Since)
    (get headers :If-Match)
    (get headers :If-None-Match)
    (get headers :If-Unmodified-Since)
    (get headers :Range)
    (->>
     [(build-canonicalized-header-string headers)
      (build-canonicalized-resource-string account container object)
      (when (seq params)
        (build-canonicalized-params-string params))]
     (remove nil?)
     (str/join "\n"))]
   (str/join "\n")))

(defn build-authorization-header
  "https://learn.microsoft.com/en-us/rest/api/storageservices/authorize-with-shared-key"
  ([method params headers account container]
   (build-authorization-header method params headers account container nil))
  ([method params headers account container object]
   (let [string-to-sign (build-canonicalized-string-to-sign
                         method params headers account container object)
         signature (->> (.getBytes ^String string-to-sign "UTF-8")
                        (util/hmac-sha256 (:key account))
                        (util/encode-base64))]
     (str "SharedKey " (:name account) ":" signature))))
