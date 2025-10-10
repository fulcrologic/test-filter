(ns com.fulcrologic.test-filter.integration.cache-test
  "Integration test for cache operations.

  This should be detected as an integration test by namespace pattern."
  (:require [fulcro-spec.core :refer [assertions specification behavior component => =fn=> =throws=>]]
            [com.fulcrologic.test-filter.cache :as cache]
            [com.fulcrologic.test-filter.analyzer :as analyzer]
            [com.fulcrologic.test-filter.git :as git]))

(specification "cache roundtrip" :group3
               {:test-targets #{com.fulcrologic.test-filter.cache/save-graph!
                                com.fulcrologic.test-filter.cache/load-graph}}
               (behavior "saves and loads a graph with explicit :test-targets metadata"
                         (let [;; Setup: Build a simple graph
                               analysis (analyzer/run-analysis {:paths ["src/main"]})
                               symbol-graph (analyzer/build-symbol-graph analysis)
                               revision (git/current-revision)]

      ;; Run: Save it
                           (cache/save-graph! symbol-graph revision)

      ;; Run: Load it back
                           (let [loaded (cache/load-graph)]

        ;; Assert
                             (assertions
                              "cache loads successfully"
                              (some? loaded) => true

                              "revision matches"
                              (:revision loaded) => revision

                              "has nodes map"
                              (map? (:nodes loaded)) => true

                              "has edges vector"
                              (sequential? (:edges loaded)) => true)))))

(specification "full cache workflow" :group4
               (behavior "runs full workflow without explicit targets (conservative integration test)"
    ;; Setup: Get or build graph
                         (let [graph (cache/get-or-build-graph :force true :paths ["src/main"])]

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
                          (cache/load-graph) => nil)))
