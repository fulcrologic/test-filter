(ns com.fulcrologic.test-filter.graph-test
  "Unit tests for graph operations."
  (:require [com.fulcrologic.test-filter.graph :as graph]
            [fulcro-spec.core :refer [=> assertions behavior component specification]]
            [loom.graph :as lg]))

;; -----------------------------------------------------------------------------
;; Test Data
;; -----------------------------------------------------------------------------

(def sample-symbol-graph
  "A mock symbol graph for testing."
  {:nodes {'app.core/handler           {:symbol 'app.core/handler :type :var :file "src/app/core.clj"}
           'app.core/process           {:symbol 'app.core/process :type :var :file "src/app/core.clj"}
           'app.db/query               {:symbol 'app.db/query :type :var :file "src/app/db.clj"}
           'app.db/save                {:symbol 'app.db/save :type :var :file "src/app/db.clj"}
           'app.core-test/handler-test {:symbol     'app.core-test/handler-test
                                        :type       :test
                                        :file       "test/app/core_test.clj"
                                        :defined-by 'clojure.test/deftest
                                        :metadata   {:test true}}
           'app.db-test/query-test     {:symbol     'app.db-test/query-test
                                        :type       :test
                                        :file       "test/app/db_test.clj"
                                        :defined-by 'clojure.test/deftest
                                        :metadata   {:test true}}}
   :edges [{:from 'app.core/handler :to 'app.core/process}
           {:from 'app.core/handler :to 'app.db/query}
           {:from 'app.core/process :to 'app.db/save}
           {:from 'app.core-test/handler-test :to 'app.core/handler}
           {:from 'app.db-test/query-test :to 'app.db/query}]})

(def integration-test-graph
  "Symbol graph with integration tests."
  {:nodes {'app.core/api              {:symbol 'app.core/api :type :var}
           'app.db/persist            {:symbol 'app.db/persist :type :var}
           'app.test/integration-test {:symbol   'app.test/integration-test
                                       :type     :test
                                       :metadata {:test true :integration? true}}
           'app.test/targeted-test    {:symbol   'app.test/targeted-test
                                       :type     :test
                                       :metadata {:test         true
                                                  :integration? true
                                                  :test-targets #{'app.core/api}}}}
   :edges [{:from 'app.test/integration-test :to 'app.core/api}
           {:from 'app.test/integration-test :to 'app.db/persist}
           {:from 'app.test/targeted-test :to 'app.core/api}]})

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(specification "build-dependency-graph"
  (behavior "creates a loom directed graph from symbol graph"
    (let [graph (graph/build-dependency-graph sample-symbol-graph)]

      (assertions
        "returns a loom graph"
        (satisfies? lg/Graph graph) => true

        "graph contains all nodes"
        (lg/has-node? graph 'app.core/handler) => true
        (lg/has-node? graph 'app.db/query) => true
        (lg/has-node? graph 'app.core-test/handler-test) => true

        "graph has correct edge count"
        (count (lg/edges graph)) => 5)))

  (behavior "creates 'uses' relationships (A -> B means A uses B)"
    (let [graph (graph/build-dependency-graph sample-symbol-graph)]

      (assertions
        "handler uses process"
        (lg/has-edge? graph 'app.core/handler 'app.core/process) => true

        "handler uses query"
        (lg/has-edge? graph 'app.core/handler 'app.db/query) => true

        "handler-test uses handler"
        (lg/has-edge? graph 'app.core-test/handler-test 'app.core/handler) => true)))

  (behavior "handles graphs with no edges"
    (let [empty-graph {:nodes {'foo/bar {:symbol 'foo/bar}}
                       :edges []}
          graph       (graph/build-dependency-graph empty-graph)]

      (assertions
        "creates graph with only nodes"
        (lg/has-node? graph 'foo/bar) => true
        (count (lg/edges graph)) => 0)))

  (behavior "skips edges with nil from or to"
    (let [graph-with-nils {:nodes {'foo/bar {:symbol 'foo/bar}
                                   'foo/baz {:symbol 'foo/baz}}
                           :edges [{:from 'foo/bar :to nil}
                                   {:from nil :to 'foo/baz}
                                   {:from 'foo/bar :to 'foo/baz}]}
          graph           (graph/build-dependency-graph graph-with-nils)]

      (assertions
        "only includes valid edge"
        (count (lg/edges graph)) => 1
        (lg/has-edge? graph 'foo/bar 'foo/baz) => true))))

(specification "transitive-dependencies"
  (let [graph (graph/build-dependency-graph sample-symbol-graph)]

    (behavior "finds all transitive dependencies of a symbol"
      (let [deps (graph/transitive-dependencies graph 'app.core/handler)]

        (assertions
          "returns a set"
          (set? deps) => true

          "includes direct dependencies"
          (contains? deps 'app.core/process) => true
          (contains? deps 'app.db/query) => true

          "includes transitive dependencies"
          (contains? deps 'app.db/save) => true

          "includes the symbol itself"
          (contains? deps 'app.core/handler) => true)))

    (behavior "handles symbols with no dependencies"
      (let [deps (graph/transitive-dependencies graph 'app.db/save)]

        (assertions
          "returns set with only the symbol"
          deps => #{'app.db/save})))

    (behavior "returns nil for non-existent symbols"
      (let [deps (graph/transitive-dependencies graph 'nonexistent/symbol)]

        (assertions
          "returns nil"
          deps => nil)))))

(specification "symbols-with-dependents"
  (let [graph (graph/build-dependency-graph sample-symbol-graph)]

    (behavior "maps symbols to the set of symbols that depend on them"
      (let [dependents (graph/symbols-with-dependents graph)]

        (assertions
          "returns a map"
          (map? dependents) => true

          "handler has test depending on it"
          (contains? (get dependents 'app.core/handler) 'app.core-test/handler-test) => true

          "process has handler and test depending on it"
          (contains? (get dependents 'app.core/process) 'app.core/handler) => true
          (contains? (get dependents 'app.core/process) 'app.core-test/handler-test) => true

          "save has transitive dependents"
          (contains? (get dependents 'app.db/save) 'app.core/process) => true
          (contains? (get dependents 'app.db/save) 'app.core/handler) => true)))

    (behavior "handles empty graphs"
      (let [empty-graph (graph/build-dependency-graph {:nodes {} :edges []})
            dependents  (graph/symbols-with-dependents empty-graph)]

        (assertions
          "returns empty map"
          dependents => {})))))

(specification "find-affected-tests"
  (let [graph        (graph/build-dependency-graph sample-symbol-graph)
        test-symbols [['app.core-test/handler-test
                       (get-in sample-symbol-graph [:nodes 'app.core-test/handler-test])]
                      ['app.db-test/query-test
                       (get-in sample-symbol-graph [:nodes 'app.db-test/query-test])]]]

    (behavior "finds tests affected by changed symbols"
      (component "when a directly tested symbol changes"
        (let [affected (graph/find-affected-tests
                         graph
                         test-symbols
                         #{'app.core/handler}
                         sample-symbol-graph)]

          (assertions
            "includes the test for that symbol"
            (contains? affected 'app.core-test/handler-test) => true

            "does not include unrelated tests"
            (contains? affected 'app.db-test/query-test) => false)))

      (component "when a transitively used symbol changes"
        (let [affected (graph/find-affected-tests
                         graph
                         test-symbols
                         #{'app.db/save}
                         sample-symbol-graph)]

          (assertions
            "includes tests that transitively depend on it"
            (contains? affected 'app.core-test/handler-test) => true)))

      (component "when multiple symbols change"
        (let [affected (graph/find-affected-tests
                         graph
                         test-symbols
                         #{'app.core/handler 'app.db/query}
                         sample-symbol-graph)]

          (assertions
            "includes all affected tests"
            (contains? affected 'app.core-test/handler-test) => true
            (contains? affected 'app.db-test/query-test) => true))))

    (behavior "handles integration tests with explicit targets"
      (let [int-graph (graph/build-dependency-graph integration-test-graph)
            int-tests [['app.test/targeted-test
                        (get-in integration-test-graph [:nodes 'app.test/targeted-test])]]]

        (component "runs test only if target changed"
          (let [affected-api (graph/find-affected-tests
                               int-graph
                               int-tests
                               #{'app.core/api}
                               integration-test-graph)]

            (assertions
              "includes test when target changes"
              (contains? affected-api 'app.test/targeted-test) => true))

          (let [affected-other (graph/find-affected-tests
                                 int-graph
                                 int-tests
                                 #{'app.db/persist}
                                 integration-test-graph)]

            (assertions
              "excludes test when other symbol changes"
              (contains? affected-other 'app.test/targeted-test) => false)))))

    (behavior "runs integration tests without targets conservatively"
      (let [int-graph (graph/build-dependency-graph integration-test-graph)
            int-tests [['app.test/integration-test
                        (get-in integration-test-graph [:nodes 'app.test/integration-test])]]]

        (component "always runs when marked as integration"
          (let [affected (graph/find-affected-tests
                           int-graph
                           int-tests
                           #{'app.db/persist}
                           integration-test-graph)]

            (assertions
              "includes integration test"
              (contains? affected 'app.test/integration-test) => true)))))

    (behavior "handles empty changed set"
      (let [affected (graph/find-affected-tests
                       graph
                       test-symbols
                       #{}
                       sample-symbol-graph)]

        (assertions
          "returns empty set when nothing changed"
          affected => #{})))

    (behavior "normalizes test symbols input"
      (component "accepts symbols without node data"
        (let [symbol-only ['app.core-test/handler-test 'app.db-test/query-test]
              affected    (graph/find-affected-tests
                            graph
                            symbol-only
                            #{'app.core/handler}
                            sample-symbol-graph)]

          (assertions
            "finds affected tests"
            (contains? affected 'app.core-test/handler-test) => true))))))

(specification "graph-stats"
  (let [graph (graph/build-dependency-graph sample-symbol-graph)]

    (behavior "returns statistics about the graph"
      (let [stats (graph/graph-stats graph)]

        (assertions
          "includes node count"
          (:node-count stats) => 6

          "includes edge count"
          (:edge-count stats) => 5

          "includes nodes list"
          (set? (set (:nodes stats))) => true
          (contains? (set (:nodes stats)) 'app.core/handler) => true)))))
