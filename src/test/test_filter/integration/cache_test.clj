(ns test-filter.integration.cache-test
  "Integration test for cache operations. 
  
  This should be detected as an integration test by namespace pattern."
  (:require [clojure.test :refer [deftest is testing]]
            [test-filter.cache :as cache]
            [test-filter.analyzer :as analyzer]
            [test-filter.git :as git]))

(deftest ^{:test-targets #{test-filter.cache/save-graph!
                           test-filter.cache/load-graph}}
  test-cache-roundtrip
  "Tests that we can save and load a graph.
  This test has explicit :test-targets metadata."
  (testing "Cache save and load cycle"
    (let [;; Build a simple graph
          analysis (analyzer/run-analysis {:paths ["src/main/test_filter"]})
          symbol-graph (analyzer/build-symbol-graph analysis)
          revision (git/current-revision)

          ;; Save it
          _ (cache/save-graph! symbol-graph revision)

          ;; Load it back
          loaded (cache/load-graph)]

      (is (some? loaded) "Cache should load successfully")
      (is (= revision (:revision loaded)) "Revision should match")
      (is (map? (:nodes loaded)) "Should have nodes map")
      (is (sequential? (:edges loaded)) "Should have edges vector"))))

(deftest test-full-cache-workflow
  "Integration test without explicit targets.
  Should run conservatively since it's in .integration. namespace."
  (testing "Full workflow: analyze, save, load, invalidate"
    (let [graph (cache/get-or-build-graph :force true :paths ["src/main/test_filter"])]
      (is (some? graph) "Should get a graph")
      (is (pos? (count (:nodes graph))) "Should have nodes")

      ;; Clear cache
      (cache/clear-cache!)
      (is (nil? (cache/load-graph)) "Cache should be cleared"))))
