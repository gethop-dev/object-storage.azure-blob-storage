(defproject dev.gethop/object-storage.azure-blob-storage "0.1.1"
  :description "A Duct library for managing objects in Azure Blob Storage"
  :url "https://github.com/gethop-dev/object-storage.azure-blob-storage"
  :license {:name "Mozilla Public Licence 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :min-lein-version "2.9.8"
  :dependencies [[org.clojure/clojure "1.11.0"]
                 [integrant "0.8.1"]
                 [http-kit/http-kit "2.7.0"]
                 [dev.gethop/object-storage.core "0.1.4"]
                 [org.clojure/data.xml "0.0.8"]]
  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/CLOJARS_USERNAME
                                      :password :env/CLOJARS_PASSWORD
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/CLOJARS_USERNAME
                                      :password :env/CLOJARS_PASSWORD
                                      :sign-releases false}]]
  :profiles
  {:dev [:project/dev :profiles/dev]
   :repl {:repl-options {:host "0.0.0.0"
                         :port 4001}}
   :profiles/dev {}
   :project/dev {:dependencies [[org.clj-commons/digest "1.4.100"]]
                 :plugins [[jonase/eastwood "1.4.0"]
                           [dev.weavejester/lein-cljfmt "0.11.2"]]
                 :eastwood {:linters [:all]
                            :exclude-linters [:keyword-typos]
                            :debug [:progress :time]}}})
