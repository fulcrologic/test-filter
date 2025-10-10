(ns com.fulcrologic.test-filter.integration.cache-test
  "Integration test for cache operations.

  This should be detected as an integration test by namespace pattern."
  (:require [com.fulcrologic.test-filter.analyzer :as analyzer]
            [com.fulcrologic.test-filter.cache :as cache]
            [com.fulcrologic.test-filter.content :as content]
            [fulcro-spec.core :refer [=> assertions behavior specification]]))

(specification "cache roundtrip" :group3
  {:test-targets #{com.fulcrologic.test-filter.cache/save-graph!
                   com.fulcrologic.test-filter.cache/load-graph}}
  (behavior "saves and loads a graph with explicit :test-targets metadata"
    (let [;; Setup: Build a simple graph
          paths          ["src/main"]
          analysis       (analyzer/run-analysis {:paths paths})
          symbol-graph   (analyzer/build-symbol-graph analysis)
          content-hashes (content/hash-graph-symbols symbol-graph)]

      ;; Run: Save it
      (cache/save-graph! symbol-graph content-hashes paths)

      ;; Run: Load it back
      (let [loaded (cache/load-graph)]

        ;; Assert
        (assertions
          "cache loads successfully"
          (some? loaded) => true

          "has nodes map"
          (map? (:nodes loaded)) => true

          "has edges vector"
          (sequential? (:edges loaded)) => true

          "has content hashes"
          (map? (:content-hashes loaded)) => true

          "has paths"
          (= (:paths loaded) paths) => true)))))

(specification "full cache workflow" :group4
  (behavior "runs full workflow without explicit targets (conservative integration test)"
    ;; Setup: Build and save graph
    (let [paths          ["src/main"]
          analysis       (analyzer/run-analysis {:paths paths})
          symbol-graph   (analyzer/build-symbol-graph analysis)
          content-hashes (content/hash-graph-symbols symbol-graph)]

      (cache/save-graph! symbol-graph content-hashes paths)

      (let [graph (cache/load-graph)]
        ;; Assert: Graph is valid
        (assertions
          "gets a graph"
          (some? graph) => true

          "has nodes"
          (pos? (count (:nodes graph))) => true))

      ;; Run: Clear cache
      (cache/invalidate-cache!)

      ;; Assert: Cache is cleared
      (assertions
        "cache is cleared"
        (cache/load-graph) => nil))))
