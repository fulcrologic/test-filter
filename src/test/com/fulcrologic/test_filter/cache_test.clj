(ns com.fulcrologic.test-filter.cache-test
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.test-filter.cache :as cache]
    [fulcro-spec.core :refer [=> =fn=> assertions behavior specification]]))

(def test-analysis-cache-file ".test-cache-analysis.edn")
(def test-success-cache-file ".test-cache-success.edn")

(defn cleanup-test-files!
  "Removes test cache files if they exist."
  []
  (doseq [f [test-analysis-cache-file test-success-cache-file]]
    (when (.exists (io/file f))
      (.delete (io/file f)))))

(defn with-test-cache-files
  "Wraps test code to use temporary cache file names."
  [f]
  (cleanup-test-files!)
  (binding [cache/*cache-file*         test-analysis-cache-file
            cache/*success-cache-file* test-success-cache-file]
    (try
      (f)
      (finally
        (cleanup-test-files!)))))

;; -----------------------------------------------------------------------------
;; Analysis Cache Tests
;; -----------------------------------------------------------------------------

(specification "cache-path"
  (behavior "returns the current analysis cache file path"
    (assertions
      (cache/cache-path) => ".test-filter-cache.edn")))

(specification "save-graph!"
  (with-test-cache-files
    (fn []
      (behavior "persists a symbol graph to disk"
        (let [symbol-graph   {:nodes {:foo {:symbol 'foo :type :var}}
                              :edges [{:from 'foo :to 'bar}]
                              :files {"src/foo.clj" {:symbols ['foo]}}}
              content-hashes {'foo "hash123"}
              paths          ["src/main" "src/test"]]

          (cache/save-graph! symbol-graph content-hashes paths)

          (let [cache-file (io/file (cache/cache-path))
                data       (read-string (slurp cache-file))]
            (assertions
              "creates the cache file"
              (.exists cache-file) => true

              "contains all graph data"
              (:nodes data) => (:nodes symbol-graph)
              (:edges data) => (:edges symbol-graph)
              (:files data) => (:files symbol-graph)
              (:content-hashes data) => content-hashes
              (:paths data) => paths

              "includes analyzed-at timestamp"
              (:analyzed-at data) =fn=> string?))))

      (behavior "overwrites existing cache"
        (cache/save-graph! {:nodes {:old "data"} :edges [] :files {}} {} ["old"])
        (cache/save-graph! {:nodes {:new "data"} :edges [] :files {}} {} ["new"])

        (let [data (read-string (slurp (cache/cache-path)))]
          (assertions
            (:nodes data) => {:new "data"}
            (:paths data) => ["new"])))

      (behavior "returns the saved cache data"
        (let [result (cache/save-graph! {:nodes {} :edges [] :files {}} {} [])]
          (assertions
            (:nodes result) => {}
            (:edges result) => []
            (:files result) => {}
            (:content-hashes result) => {}
            (:paths result) => []
            (:analyzed-at result) =fn=> string?))))))

(specification "load-graph"
  (behavior "loads the analysis cache from disk"
    (with-test-cache-files
      (fn []
        (let [graph-data {:nodes          {:foo {:symbol 'foo}}
                          :edges          [{:from 'foo :to 'bar}]
                          :files          {"src/foo.clj" {:symbols ['foo]}}
                          :content-hashes {'foo "hash123"}
                          :paths          ["src/main"]
                          :analyzed-at    "2024-01-01"}]

          (spit (cache/cache-path) (pr-str graph-data))

          (let [loaded (cache/load-graph)]
            (assertions
              (:nodes loaded) => (:nodes graph-data)
              (:edges loaded) => (:edges graph-data)
              (:files loaded) => (:files graph-data)
              (:content-hashes loaded) => (:content-hashes graph-data)
              (:paths loaded) => (:paths graph-data)
              (:analyzed-at loaded) => (:analyzed-at graph-data)))))))

  (behavior "returns nil when cache doesn't exist"
    (with-test-cache-files
      (fn []
        (assertions
          (cache/load-graph) => nil))))

  (behavior "returns nil when cache is corrupted"
    (with-test-cache-files
      (fn []
        (spit (cache/cache-path) "{{{invalid")

        (assertions
          (cache/load-graph) => nil)))))

(specification "invalidate-cache!"
  (behavior "deletes the analysis cache file"
    (with-test-cache-files
      (fn []
        (spit (cache/cache-path) "some data")

        (cache/invalidate-cache!)

        (assertions
          (.exists (io/file (cache/cache-path))) => false))))

  (behavior "returns true when file existed"
    (with-test-cache-files
      (fn []
        (spit (cache/cache-path) "data")

        (assertions
          (cache/invalidate-cache!) => true))))

  (behavior "returns nil when file doesn't exist"
    (with-test-cache-files
      (fn []
        (assertions
          (cache/invalidate-cache!) => nil)))))

;; -----------------------------------------------------------------------------
;; Success Cache Tests
;; -----------------------------------------------------------------------------

(specification "success-cache-path"
  (behavior "returns the current success cache file path"
    (assertions
      (cache/success-cache-path) => ".test-filter-success.edn")))

(specification "load-success-cache"
  (behavior "loads the success cache from disk"
    (with-test-cache-files
      (fn []
        (let [cache-data {'my.app/foo "hash1"
                          'my.app/bar "hash2"}]

          (spit (cache/success-cache-path) (pr-str cache-data))

          (assertions
            (cache/load-success-cache) => cache-data)))))

  (behavior "returns empty map when cache doesn't exist"
    (with-test-cache-files
      (fn []
        (assertions
          (cache/load-success-cache) => {}))))

  (behavior "returns empty map when cache is corrupted"
    (with-test-cache-files
      (fn []
        (spit (cache/success-cache-path) "{{{invalid")

        (assertions
          (cache/load-success-cache) => {})))))

(specification "save-success-cache!"
  (behavior "persists success cache to disk"
    (with-test-cache-files
      (fn []
        (let [cache-data {'my.app/foo "hash1"
                          'my.app/bar "hash2"}]

          (cache/save-success-cache! cache-data)

          (let [cache-file (io/file (cache/success-cache-path))]
            (assertions
              "creates the cache file"
              (.exists cache-file) => true

              "can be loaded back successfully"
              (cache/load-success-cache) => cache-data))))))

  (behavior "overwrites existing cache"
    (with-test-cache-files
      (fn []
        (cache/save-success-cache! {'old "hash"})
        (cache/save-success-cache! {'new "hash"})

        (assertions
          (cache/load-success-cache) => {'new "hash"}))))

  (behavior "returns the saved cache data"
    (with-test-cache-files
      (fn []
        (let [cache-data {'my.app/foo "hash1"}
              result     (cache/save-success-cache! cache-data)]

          (assertions
            result => cache-data))))))

(specification "update-success-cache!"
  (behavior "merges new hashes into existing success cache"
    (with-test-cache-files
      (fn []
        (cache/save-success-cache! {'my.app/foo "hash1"
                                    'my.app/bar "hash2"})

        (cache/update-success-cache! {'my.app/bar "newhash2"
                                      'my.app/baz "hash3"})

        (let [result (cache/load-success-cache)]
          (assertions
            "preserves unchanged entries"
            (get result 'my.app/foo) => "hash1"

            "updates existing entries"
            (get result 'my.app/bar) => "newhash2"

            "adds new entries"
            (get result 'my.app/baz) => "hash3")))))

  (behavior "creates cache if it doesn't exist"
    (with-test-cache-files
      (fn []
        (cache/update-success-cache! {'my.app/foo "hash1"})

        (assertions
          (cache/load-success-cache) => {'my.app/foo "hash1"}))))

  (behavior "handles empty verified hashes"
    (with-test-cache-files
      (fn []
        (cache/save-success-cache! {'my.app/foo "hash1"})

        (cache/update-success-cache! {})

        (assertions
          (cache/load-success-cache) => {'my.app/foo "hash1"})))))

(specification "invalidate-success-cache!"
  (behavior "deletes the success cache file"
    (with-test-cache-files
      (fn []
        (spit (cache/success-cache-path) "some data")

        (cache/invalidate-success-cache!)

        (assertions
          (.exists (io/file (cache/success-cache-path))) => false))))

  (behavior "returns true when file existed"
    (with-test-cache-files
      (fn []
        (spit (cache/success-cache-path) "data")

        (assertions
          (cache/invalidate-success-cache!) => true))))

  (behavior "returns nil when file doesn't exist"
    (with-test-cache-files
      (fn []
        (assertions
          (cache/invalidate-success-cache!) => nil)))))

(specification "invalidate-all-caches!"
  (behavior "deletes both analysis and success cache files"
    (with-test-cache-files
      (fn []
        (spit (cache/cache-path) "analysis data")
        (spit (cache/success-cache-path) "success data")

        (cache/invalidate-all-caches!)

        (assertions
          "analysis cache is deleted"
          (.exists (io/file (cache/cache-path))) => false

          "success cache is deleted"
          (.exists (io/file (cache/success-cache-path))) => false)))))

;; -----------------------------------------------------------------------------
;; Cache Status Tests
;; -----------------------------------------------------------------------------

(specification "cache-status"
  (behavior "reports status when both caches exist"
    (with-test-cache-files
      (fn []
        (spit (cache/cache-path) "analysis data")
        (spit (cache/success-cache-path) "success data")

        (let [status (cache/cache-status)]
          (assertions
            "reports analysis cache exists"
            (get-in status [:analysis-cache :exists?]) => true
            (get-in status [:analysis-cache :path]) => test-analysis-cache-file
            (get-in status [:analysis-cache :size]) =fn=> pos?
            (get-in status [:analysis-cache :last-modified]) =fn=> #(instance? java.util.Date %)

            "reports success cache exists"
            (get-in status [:success-cache :exists?]) => true
            (get-in status [:success-cache :path]) => test-success-cache-file
            (get-in status [:success-cache :size]) =fn=> pos?
            (get-in status [:success-cache :last-modified]) =fn=> #(instance? java.util.Date %))))))

  (behavior "reports status when caches don't exist"
    (with-test-cache-files
      (fn []
        (let [status (cache/cache-status)]
          (assertions
            "reports analysis cache doesn't exist"
            (get-in status [:analysis-cache :exists?]) => false
            (get-in status [:analysis-cache :path]) => test-analysis-cache-file
            (get-in status [:analysis-cache :size]) => nil
            (get-in status [:analysis-cache :last-modified]) => nil

            "reports success cache doesn't exist"
            (get-in status [:success-cache :exists?]) => false
            (get-in status [:success-cache :path]) => test-success-cache-file
            (get-in status [:success-cache :size]) => nil
            (get-in status [:success-cache :last-modified]) => nil)))))

  (behavior "reports mixed status"
    (with-test-cache-files
      (fn []
        (spit (cache/cache-path) "analysis data")

        (let [status (cache/cache-status)]
          (assertions
            (get-in status [:analysis-cache :exists?]) => true
            (get-in status [:success-cache :exists?]) => false))))))
