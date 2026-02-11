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
  (:import (java.io File)
           (java.time ZonedDateTime
                      ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def ^:const default-presigned-url-lifespan
  "Default presigned urls lifespan, expressed in minutes"
  60)

(def ^:private metadata-headers->request-headers
  {:content-type :Content-Type
   :content-disposition :x-ms-blob-content-disposition
   :content-encoding :Content-Encoding
   :content-length :Content-Length})

(def ^:private supported-content-headers
  (into [] (keys metadata-headers->request-headers)))

(def ^:private supported-metadata
  (into supported-content-headers
        [:filename]))

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

(defn- content-disposition-header
  [filename content-disposition-type]
  (str
   (case content-disposition-type
     :attachment "attachment"
     :inline "inline")
   "; filename*=UTF-8''" (util/percent-encode-str filename)))

(defn- object-key->filename
  "Try to infer a filename from the object key.

  The `/` character is the separator of the object-key \"parts\". Thus
  the last part must be what's intented to be the filename of the
  object..

  Things can become quite complicated if object-key contains the `.`
  or `..` prefixes, or consecutive `/` characters, or when the
  object-key ends in a trailing `/`.  But sane object-keys shouldn't
  contain such edge cases. So we apply a naïve strategy."
  [object-key]
  (peek (str/split object-key #"/+")))

(defn- put-object*
  [{:keys [account container]} object-id object opts]
  (let [metadata (-> (:metadata opts)
                     (set/rename-keys {:object-size :content-length})
                     (select-keys supported-metadata))
        content-length (or
                        (:content-length metadata)
                        (and (instance? File object) (.length ^File object)))]
    (if-not content-length
      {:success? false
       :error-details {:reason :could-not-determine-content-length}}
      (let [filename (or (:filename metadata)
                         (object-key->filename object-id))
            update-fn (fnil (partial content-disposition-header filename)
                            ;; "inline" content-disposition is the default so
                            ;; setting it when not passed in as an argument is
                            ;; idempotent, but simplifies things.
                            :inline)
            content-headers (-> metadata
                                (select-keys supported-content-headers)
                                (update :content-disposition update-fn)
                                (assoc :content-length content-length)
                                (set/rename-keys metadata-headers->request-headers))
            headers (into {:x-ms-version "2019-02-02"
                           :x-ms-date (util/get-current-date-time-in-http-date-header-format)
                           :x-ms-blob-type "BlockBlob"}
                          content-headers)
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
  (if (= source-object-id destination-object-id)
    ;; Copying object to itself. No need to do anything.
    {:success? true}
    (let [src-url (util/build-resource-url account container source-object-id)
          dst-url (util/build-resource-url account container destination-object-id)
          headers {:x-ms-version "2019-02-02"
                   :x-ms-date (util/get-current-date-time-in-http-date-header-format)
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
         :error-details (dissoc result :opts)}))))

(defn- xml-input-stream->xml-string
  [result-body]
  (try
    (-> result-body
        (xml/parse :supporting-external-entities false)
        (xml/emit-str))
    (catch Throwable _
      nil)))

(defn- get-object*
  [{:keys [account container]} object-id _opts]
  (let [headers {:x-ms-version "2019-02-02"
                 :x-ms-date (util/get-current-date-time-in-http-date-header-format)}
        params {}
        authorization-header (auth/build-authorization-header
                              :get params headers account container {:id object-id})
        request {:url (util/build-resource-url account container object-id)
                 :query-params params
                 :method :get
                 :headers headers
                 :as :stream}
        {:keys [status] :as result} (make-request request authorization-header)]
    (if (and status (<= 200 status 299))
      {:success? true
       :object (:body result)
       :metadata (-> (:headers result)
                     (select-keys supported-metadata)
                     (set/rename-keys {:content-length :object-size})
                     (update :object-size (fn [s]
                                            (Long/parseLong s))))}
      {:success? false
       :error-details (-> result
                          (dissoc :opts)
                          (update :body #(or (xml-input-stream->xml-string %)
                                             "Empty or unreadable body")))})))

(defn- delete-object*
  [{:keys [account container]} object-id opts]
  (let [headers {:x-ms-version "2019-02-02"
                 :x-ms-date (util/get-current-date-time-in-http-date-header-format)}
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
  [{:keys [account container presigned-url-lifespan]} object-id
   {:keys [object-public-url?]
    :or {object-public-url? false}
    :as opts}]
  (if object-public-url?
    {:success? true
     :object-url (util/build-resource-url account container object-id)}
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
       :object-url url})))

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
                 :x-ms-date (util/get-current-date-time-in-http-date-header-format)}
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
      (try
        (let [parsed-body (xml/parse (:body result) :supporting-external-entities false)]
          {:success? true
           :blobs (->> (:content parsed-body)
                       (xml-search-tag-content :Blobs))
           :next-marker (->> (:content parsed-body)
                             (xml-search-tag-content :NextMarker)
                             (first))})
        (catch Throwable _
          {:success? false
           :error-details (-> result
                              (dissoc :opts)
                              (assoc :error :could-not-parse-response-body))}))
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
