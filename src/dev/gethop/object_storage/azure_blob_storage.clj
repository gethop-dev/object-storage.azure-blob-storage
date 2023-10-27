;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns dev.gethop.object-storage.azure-blob-storage
  (:require [clojure.data.xml :as xml]
            [clojure.set :as set]
            [clojure.string :as str]
            [dev.gethop.object-storage.azure-blob-storage.auth :as auth]
            [dev.gethop.object-storage.azure-blob-storage.shared-access-signature :as sas]
            [dev.gethop.object-storage.azure-blob-storage.util :as util]
            [dev.gethop.object-storage.core :as core]
            [integrant.core :as ig]
            [org.httpkit.client :as http])
  (:import [java.io File]
           [java.time ZonedDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:const default-presigned-url-lifespan
  "Default presigned urls lifespan, expressed in minutes"
  60)

(defn- connection-string->account
  [connection-string]
  (-> (->> (str/split connection-string #";")
           (map #(str/split % #"=" 2))
           (reduce (fn [m [k v]] (assoc m k v)) {}))
      (set/rename-keys {"DefaultEndpointsProtocol" :default-endpoint-protocol
                        "EndpointSuffix" :default-endpoint-suffix
                        "AccountName" :name
                        "AccountKey" :key})
      (update :key util/decode-base64)))

(defn- make-request
  [request authorization-header]
  (-> request
      (assoc-in [:headers :authorization] authorization-header)
      (update :headers util/update-map-keys name)
      (http/request)
      (deref)))

(defn- put-object*
  [{:keys [account container]} object-id object opts]
  (let [content-length (or
                        (get-in opts [:metadata :object-size])
                        (and (instance? File object) (.length ^File object)))]
    (if-not content-length
      {:success? false
       :error-details {:reason :could-not-determine-content-length}}
      (let [headers {:x-ms-version "2019-02-02"
                     :x-ms-date (util/get-current-date-time-in-rfc-1123)
                     :x-ms-blob-type "BlockBlob"
                     :Content-Length content-length}
            params {}
            authorization-header (auth/build-authorization-header
                                  :put params headers account container {:id object-id})
            request {:url (util/build-resource-url account container object-id)
                     :query-params params
                     :method :put
                     :headers headers
                     :body object}
            {:keys [status] :as result} (make-request request authorization-header)]
        (if (and status (<= 200 status 299))
          {:success? true}
          {:success? false
           :error-details (dissoc result :opts)})))))

(defn- copy-object*
  [{:keys [account container]} source-object-id destination-object-id _opts]
  (let [src-url (util/build-resource-url account container source-object-id)
        dst-url (util/build-resource-url account container destination-object-id)
        headers {:x-ms-version "2019-02-02"
                 :x-ms-date (util/get-current-date-time-in-rfc-1123)
                 :x-ms-copy-source src-url}
        params {}
        authorization-header (auth/build-authorization-header
                              :put params headers account container {:id destination-object-id})
        request {:url dst-url
                 :query-params params
                 :method :put
                 :headers headers}
        {:keys [status] :as result} (make-request request authorization-header)]
    (if (and status (<= 200 status 299))
      {:success? true}
      {:success? false
       :error-details (dissoc result :opts)})))

(defn- get-object*
  [{:keys [account container]} object-id _opts]
  (let [headers {:x-ms-version "2019-02-02"
                 :x-ms-date (util/get-current-date-time-in-rfc-1123)}
        params {}
        authorization-header (auth/build-authorization-header
                              :get params headers account container {:id object-id})
        request {:url (util/build-resource-url account container object-id)
                 :query-params params
                 :method :get
                 :headers headers}
        {:keys [status] :as result} (make-request request authorization-header)]
    (if (and status (<= 200 status 299))
      {:success? true
       :object (:body result)}
      {:success? false
       :error-details (dissoc result :opts)})))

(defn- delete-object*
  [{:keys [account container]} object-id opts]
  (let [headers {:x-ms-version "2019-02-02"
                 :x-ms-date (util/get-current-date-time-in-rfc-1123)}
        params (cond-> {}
                 (not (:permanently-delete? opts))
                 (assoc :deletetype "permanent"))
        authorization-header (auth/build-authorization-header
                              :delete params headers account container {:id object-id})
        request {:url (util/build-resource-url account container object-id)
                 :query-params params
                 :method :delete
                 :headers headers}
        {:keys [status] :as result} (make-request request authorization-header)]
    (if (and status (<= 200 status 299))
      {:success? true}
      {:success? false
       :error-details (dissoc result :opts)})))

(defn- build-object-url-permissions-opts
  [method]
  (case method
    :create "c"
    :update "w"
    :delete "d"
    "r"))

(defn- build-object-url-content-disposition-opt
  [{:keys [content-disposition filename] :as _response-headers}]
  (cond-> (name (or content-disposition :attachment))
    filename
    (str "; filename=" filename)))

(defn- build-object-url-expire-opt
  [lifespan]
  (.format
   (.plusMinutes
    (ZonedDateTime/now ZoneOffset/UTC)
    lifespan)
   (DateTimeFormatter/ofPattern "YYYY-MM-dd'T'HH:mm:ss'Z'")))

(defn- get-object-url*
  [{:keys [account container presigned-url-lifespan]} object-id opts]
  (let [signed {:version "2020-12-06"
                :resource "b"
                :protocol "https"}
        policy {:expiry (build-object-url-expire-opt presigned-url-lifespan)
                :permissions (build-object-url-permissions-opts (:method opts))}
        content-disposition (build-object-url-content-disposition-opt opts)
        response-headers (-> (select-keys opts (keys sas/response-headers-opt-mapping))
                             (assoc :content-disposition content-disposition)
                             (update :content-type #(or % "application/octet-stream")))
        url (sas/build-presigned-url
             signed policy response-headers
             account container {:id object-id})]
    {:success? true
     :object-url url}))

(defn- xml-search-tag-content
  [tag xml-data]
  (some
   #(when (= tag (:tag %)) (:content %))
   xml-data))

(defn- xml-blob->object
  [{:keys [content] :as _xml-blob}]
  {:object-id (first (xml-search-tag-content :Name content))
   :size (->> (xml-search-tag-content :Properties content)
              (xml-search-tag-content :Content-Length)
              (first)
              (Integer/parseInt))
   :last-modified (->> (xml-search-tag-content :Properties content)
                       (xml-search-tag-content :Last-Modified)
                       (first)
                       (util/parse-rfc-1123-date))})

(defn- list-blobs*
  [{:keys [account container]} parent-object-id marker]
  (let [headers {:x-ms-version "2019-02-02"
                 :x-ms-date (util/get-current-date-time-in-rfc-1123)}
        params (cond-> {:restype "container"
                        :comp "list"}
                 parent-object-id
                 (assoc :prefix parent-object-id)
                 marker
                 (assoc :marker marker))
        authorization-header (auth/build-authorization-header
                              :get params headers account container)
        request {:url (util/build-resource-url account container)
                 :query-params params
                 :method :get
                 :headers headers
                 :as :stream}
        {:keys [status] :as result} (make-request request authorization-header)]
    (if (and status (<= 200 status 299))
      (let [parsed-body (xml/parse (:body result))]
        {:success? true
         :blobs (->> (:content parsed-body)
                     (xml-search-tag-content :Blobs))
         :next-marker (->> (:content parsed-body)
                           (xml-search-tag-content :NextMarker)
                           (first))})
      {:success? false
       :error-details (dissoc result :opts)})))

(defn- list-blobs
  [this parent-object-id]
  (loop [blob-list []
         marker nil]
    (let [list-blobs-result (list-blobs* this parent-object-id marker)]
      (if-not (:success? list-blobs-result)
        {:success? false
         :error-details list-blobs-result}
        (let [new-blob-list (concat blob-list (:blobs list-blobs-result))
              next-marker (:next-marker list-blobs-result)]
          (if next-marker
            (recur new-blob-list next-marker)
            {:success? true
             :blobs new-blob-list}))))))

(defn- list-objects*
  [this parent-object-id]
  (let [result (list-blobs this parent-object-id)]
    (if (:success? result)
      {:success? true
       :objects (map xml-blob->object (:blobs result))}
      {:success? false
       :error-details result})))

(defrecord AzureBlobStorage
           [account container]
  core/ObjectStorage
  (put-object [this object-id object]
    (put-object* this object-id object {}))
  (put-object [this object-id object opts]
    (put-object* this object-id object opts))

  (copy-object [this source-object-id destination-object-id]
    (copy-object* this source-object-id destination-object-id {}))
  (copy-object [this source-object-id destination-object-id opts]
    (copy-object* this source-object-id destination-object-id opts))

  (get-object [this object-id]
    (get-object* this object-id {}))
  (get-object [this object-id opts]
    (get-object* this object-id opts))

  (get-object-url [this object-id]
    (get-object-url* this object-id {}))
  (get-object-url [this object-id opts]
    (get-object-url* this object-id opts))

  (delete-object [this object-id]
    (delete-object* this object-id {}))
  (delete-object [this object-id opts]
    (delete-object* this object-id opts))

  (list-objects [this parent-object-id]
    (list-objects* this parent-object-id)))

(defn init-record
  [{:keys [account container presigned-url-lifespan]
    :or {presigned-url-lifespan default-presigned-url-lifespan}
    {:keys [connection-string]} :account}]
  (map->AzureBlobStorage
   {:account (if connection-string
               (connection-string->account connection-string)
               account)
    :container container
    :presigned-url-lifespan presigned-url-lifespan}))

(defmethod ig/init-key :dev.gethop.object-storage/azure-blob-storage
  [_ config]
  (init-record config))
