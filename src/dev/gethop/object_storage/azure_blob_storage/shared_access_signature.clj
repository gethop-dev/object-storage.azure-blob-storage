;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns dev.gethop.object-storage.azure-blob-storage.shared-access-signature
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dev.gethop.object-storage.azure-blob-storage.util :as util]
            [org.httpkit.client :as http]))

(def ^:const signed-opt-mapping
  {:identifier :si
   :protocol :spr
   :version :sv
   :resource :sr
   :snapshot-time :fixme
   :encryption-scope :ses})

(def ^:const policy-opt-mapping
  {:permissions :sp
   :start :st
   :expiry :se
   :ip :sip})

(def ^:const response-headers-opt-mapping
  {:cache-control :rscc
   :content-disposition :rscd
   :content-encoding :rsce
   :content-type :rsct
   :content-language :rscl})

(defn- build-canonicalized-resource-string
  [account container object]
  (format "/blob/%s/%s/%s"
          (:name account)
          (:name container)
          (:id object)))

(defn- build-canonicalized-string-to-sign
  [query-params account container object]
  (->>
   [(get query-params :sp)
    (get query-params :st)
    (get query-params :se)
    (build-canonicalized-resource-string account container object)
    (get query-params :si)
    (get query-params :sip)
    (get query-params :spr)
    (get query-params :sv)
    (get query-params :sr)
    (get query-params :fixme) ;;snapshot-time
    (get query-params :ses)
    (get query-params :rscc)
    (get query-params :rscd)
    (get query-params :rsce)
    (get query-params :rscl)
    (get query-params :rsct)]
   (str/join "\n")))

(defn- sas-opts->query-params
  [signed policy response-headers]
  (merge
   (set/rename-keys signed signed-opt-mapping)
   (set/rename-keys policy policy-opt-mapping)
   (set/rename-keys response-headers response-headers-opt-mapping)))

(defn build-presigned-url
  "https://learn.microsoft.com/en-us/rest/api/storageservices/create-service-sas"
  [signed policy response-headers account container object]
  (let [query-params (sas-opts->query-params signed policy response-headers)
        string-to-sign (build-canonicalized-string-to-sign
                        query-params account container object)
        signature (->> (.getBytes ^String string-to-sign "UTF-8")
                       (util/hmac-sha256 (:key account))
                       (util/encode-base64))
        all-query-params (assoc query-params :sig signature)]
    (format "%s?%s"
            (util/build-resource-url account container (:id object))
            (http/query-string all-query-params))))
