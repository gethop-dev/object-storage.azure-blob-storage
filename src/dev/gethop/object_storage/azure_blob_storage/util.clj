;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns dev.gethop.object-storage.azure-blob-storage.util
  (:require [ring.util.codec :as ring.codec])
  (:import [java.time ZonedDateTime ZoneOffset ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Base64 Locale]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn encode-base64
  "Encodes a byte[] as String using Base64"
  [^bytes src]
  (.encodeToString (Base64/getEncoder) src))

(defn decode-base64
  "Returns a byte[] from a Base64 encoded String"
  [^String src]
  (.decode (Base64/getDecoder) src))

(defn hmac-sha256
  "Returns the calculated HMAC SHA256 of 'data' using 'key'."
  [^bytes key ^bytes data]
  (let [algo "HmacSHA256"
        mac (Mac/getInstance algo)]
    (.init mac (SecretKeySpec. key algo))
    (.doFinal mac data)))

(defn get-current-date-time-in-http-date-header-format
  []
  (.format
   (ZonedDateTime/now ZoneOffset/UTC)
   (.withZone
    (DateTimeFormatter/ofPattern
     "EEE, dd MMM yyyy HH:mm:ss z"
     Locale/ENGLISH)
    (ZoneId/of "GMT"))))

(defn parse-rfc-1123-date
  [s]
  (ZonedDateTime/parse s DateTimeFormatter/RFC_1123_DATE_TIME))

(defn update-map-keys
  [m update-fn & args]
  (reduce-kv
   (fn [m k v]
     (assoc m (apply update-fn k args) v))
   {}
   m))

(defn url-encode-str
  [^String s]
  (ring.codec/url-encode s "UTF-8"))

(defn build-resource-url
  ([account container]
   (format "%s://%s.blob.%s/%s"
           (get account :default-endpoint-protocol)
           (get account :name)
           (get account :default-endpoint-suffix)
           (url-encode-str (get container :name))))
  ([account container object-id]
   (format "%s/%s"
           (build-resource-url account container)
           (url-encode-str object-id))))
