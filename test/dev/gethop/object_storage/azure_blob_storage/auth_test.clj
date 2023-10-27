(ns dev.gethop.object-storage.azure-blob-storage.auth-test
  (:require [clojure.test :refer :all]
            [dev.gethop.object-storage.azure-blob-storage.auth :as auth]))

(deftest test-build-canonicalized-string-to-sign
  (is
   (= "GET\n\n\n\n\n\n\n\n\n\n\n\nx-ms-date:Fri, 26 Jun 2015 23:39:12 GMT\nx-ms-version:2015-02-21\n/myaccount/mycontainer\ncomp:metadata\nrestype:container\ntimeout:20"
      (auth/build-canonicalized-string-to-sign
       :get
       {:comp "metadata" :restype "container" :timeout 20}
       {:x-ms-date "Fri, 26 Jun 2015 23:39:12 GMT" :x-ms-version "2015-02-21"}
       {:name "myaccount"}
       {:name "mycontainer"}
       nil))))
