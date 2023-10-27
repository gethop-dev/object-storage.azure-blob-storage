;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns dev.gethop.object-storage.azure-blob-storage-test
  (:require [clj-commons.digest :as digest]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [dev.gethop.object-storage.azure-blob-storage :as azure-blob-storage]
            [dev.gethop.object-storage.core :as core]
            [org.httpkit.client :as http])
  (:import [java.io InputStream]
           [java.net URL]
           [java.util UUID]))

(def config {:account {:connection-string (System/getenv "TEST_AZURE_BLOB_STORAGE_ACCOUNT_CONNECTION_STRING")}
             :container {:name (System/getenv "TEST_AZURE_BLOB_STORAGE_CONTAINER_NAME")}
             :presigned-url-lifespan 1})

(def test-file-1-path "test-file-1")
(def test-file-2-path "test-file-2")

(defn setup []
  (spit test-file-1-path {:hello :world})
  (spit test-file-2-path [:apples :bananas]))

(defn teardown []
  (io/delete-file test-file-1-path)
  (io/delete-file test-file-2-path))

(defn with-test-files [f]
  (setup)
  (f)
  (teardown))

(use-fixtures :each with-test-files)

(defn http-request
  [request]
  (let [request (assoc request :as :auto)
        {:keys [error status] :as response} @(http/request request)]
    (if error
      :error
      (if (<= 200 status 299)
        response
        :not-2xx))))

(deftest ^:integration put-get-file-test
  (let [record (azure-blob-storage/init-record config)
        file-key (str "integration-test-" (UUID/randomUUID))
        file (io/file test-file-1-path)]
    (testing "testing put-object"
      (let [put-result (core/put-object record file-key file)]
        (is (:success? put-result))))
    (testing "testing get-object"
      (let [get-result (core/get-object record file-key)]
        (is (:success? get-result))
        (is (instance? InputStream (:object get-result)))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:object get-result))))))
    (core/delete-object record file-key)))

(deftest ^:integration put-get-stream-test
  (let [record (azure-blob-storage/init-record config)
        file-key (str "integration-test-" (UUID/randomUUID))
        file (io/file test-file-1-path)]
    (testing "testing put-object stream"
      (let [opts {:metadata {:object-size (.length file)}}
            stream (io/input-stream file)
            put-result (core/put-object record file-key stream opts)]
        (is (:success? put-result))))
    (testing "testing put-object stream with no size specified"
      (let [stream (io/input-stream file)
            put-result (core/put-object record file-key stream)]
        (is (false? (:success? put-result)))))
    (testing "testing get-object stream"
      (let [get-result (core/get-object record file-key)]
        (is (:success? get-result))
        (is (instance? InputStream (:object get-result)))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:object get-result))))))
    (core/delete-object record file-key)))

(deftest ^:integration replace-object-test
  (let [record (azure-blob-storage/init-record config)
        file-key (str "integration-test-" (UUID/randomUUID))
        file-1 (io/file test-file-1-path)
        file-2 (io/file test-file-2-path)]
    (testing "testing replace object is succesfull"
      (let [put-result-1 (core/put-object record file-key file-1)
            put-result-2 (core/put-object record file-key file-2)
            get-result (core/get-object record file-key)]
        (is (:success? put-result-1))
        (is (:success? put-result-2))
        (is (:success? get-result))
        (is (= (digest/sha-256 file-2)
               (digest/sha-256 (:object get-result))))))
    (core/delete-object record file-key)))

(deftest ^:integration copy-get-file-test
  (let [record (azure-blob-storage/init-record config)
        src-key (str "integration-test-" (UUID/randomUUID))
        file (io/file test-file-1-path)]
    (testing "testing put-object"
      (let [put-result (core/put-object record src-key file)]
        (is (:success? put-result))))
    (testing "testing copy-object"
      (let [dst-key (str "integration-test-" (UUID/randomUUID))
            copy-result (core/copy-object record src-key dst-key)
            get-result (core/get-object record dst-key)]
        (is (:success? copy-result))
        (is (:success? get-result))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:object get-result))))
        (core/delete-object record dst-key)))
    (core/delete-object record src-key)))

(deftest ^:integration delete-test
  (let [record (azure-blob-storage/init-record config)
        file-key (str "integration-test-" (UUID/randomUUID))
        file (io/file test-file-1-path)]
    (testing "testing delete-object for object that doesn't exist"
      (let [delete-result (core/delete-object record file-key)]
        (is (false? (:success? delete-result)))))
    (core/put-object record file-key file)
    (testing "testing delete-object"
      (let [delete-result (core/delete-object record file-key)]
        (is (:success? delete-result))))
    (testing "testing delete-object for object that has been deleted"
      (let [delete-result (core/delete-object record file-key)]
        (is (false? (:success? delete-result)))))))

(deftest ^:integration presigned-url-test
  (let [record (azure-blob-storage/init-record config)
        file-key (str "integration-test-" (UUID/randomUUID))
        file (io/file test-file-1-path)]
    (core/put-object record file-key file)
    (testing "testing default presigned url (defaults to :read operation)"
      (let [result (core/get-object-url record file-key)
            url (:object-url result)]
        (is (:success? result))
        (is (string? url))
        (is (URL. url))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:body (http-request {:url url :method :get})))))))
    (testing "testing default presigned url for :create method"
      (let [new-file-key (str file-key "-new")
            get-url-result (core/get-object-url record new-file-key {:method :create})
            url (:object-url get-url-result)
            create-result (http-request {:url url
                                         :method :put
                                         :body file
                                         :headers {"x-ms-blob-type" "BlockBlob"}})
            get-result (core/get-object record new-file-key)]
        (is (:success? get-url-result))
        (is (string? url))
        (is (URL. url))
        (is (not= :not-2xx create-result))
        (is (:success? get-result))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:object get-result))))
        (core/delete-object record new-file-key)))
    (testing "testing :create presigned url throws exception when using :read method"
      (let [result (core/get-object-url record file-key {:method :create})
            url (:object-url result)]
        (is (:success? result))
        (is (string? url))
        (is (URL. url))
        (is (= :not-2xx
               (http-request {:url url :method :get})))))
    (testing "testing :read presigned url with specific filename"
      (let [get-url-result (core/get-object-url record file-key {:filename "test.docx"})
            url (:object-url get-url-result)
            get-result (http-request {:url url :method :get})]
        (is (:success? get-url-result))
        (is (string? url))
        (is (URL. url))
        (is (=  "attachment; filename=test.docx"
                (get-in get-result [:headers :content-disposition])))))
    (core/delete-object record file-key)))

(deftest ^:integration list-test
  (let [record (azure-blob-storage/init-record config)
        file-key-1 (str "integration-test/integration-test-" (UUID/randomUUID))
        file-key-2 (str "integration-test-2/integration-test-" (UUID/randomUUID))
        file-1 (io/file test-file-1-path)
        file-2 (io/file test-file-2-path)]
    (core/put-object record file-key-1 file-1)
    (core/put-object record file-key-2 file-2)
    (testing "testing list-objects"
      (let [list-result (core/list-objects record "integration-test/")]
        (is (:success? list-result))
        (is (some (fn [{:keys [object-id]}]
                    (= object-id file-key-1))
                  (:objects list-result)))
        (is (not-any? (fn [{:keys [object-id]}]
                        (= object-id file-key-2))
                      (:objects list-result)))
        (is (every? #(and (:object-id %)
                          (:last-modified %)
                          (:size %))
                    (:objects list-result)))))
    (core/delete-object record file-key-1)
    (core/delete-object record file-key-2)))

(deftest ^:integration non-ascii-object-ids-test
  (let [record (azure-blob-storage/init-record config)
        file-base "integration-test-with-ñ$#@|¿?+*-characters-"
        file-key (str file-base (UUID/randomUUID))
        file (io/file test-file-1-path)]
    (testing "testing create with non ascii characters"
      (let [put-result (core/put-object record file-key file)]
        (is (:success? put-result))))
    (testing "testing get with non ascii characters"
      (let [get-result (core/get-object record file-key)]
        (is (:success? get-result))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:object get-result))))))
    (testing "testing list with non ascii characters"
      (let [list-result (core/list-objects record file-key)]
        (is (:success? list-result))
        (is (= 1 (count (:objects list-result))))))
    (testing "testing copy with non ascii characters"
      (let [dst-key (str file-base (UUID/randomUUID))
            copy-result (core/copy-object record file-key dst-key)]
        (is (:success? copy-result))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:object (core/get-object record dst-key)))))
        (core/delete-object record dst-key)))
    (testing "testing presigned-url with non ascii characters"
      (let [url-result (core/get-object-url record file-key)
            url (:object-url url-result)]
        (is (:success? url-result))
        (is (string? url))
        (is (= (digest/sha-256 file)
               (digest/sha-256 (:body (http-request {:url url :method :get})))))))
    (testing "testing delete with non ascii characters"
      (let [delete-result (core/delete-object record file-key)]
        (is (:success? delete-result))))))
