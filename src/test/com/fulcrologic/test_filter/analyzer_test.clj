(ns com.fulcrologic.test-filter.analyzer-test
  (:require [com.fulcrologic.test-filter.analyzer :as analyzer]
            [fulcro-spec.core :refer [=> assertions behavior specification]]))

(specification "run-analysis"
  (behavior "can run clj-kondo and parse results"
    (let [analysis (analyzer/run-analysis {:paths ["src/main"]})]

      (assertions
        "returns a map"
        (map? analysis) => true

        "contains analysis key"
        (contains? analysis :analysis) => true

        "analysis contains var-definitions"
        (contains? (:analysis analysis) :var-definitions) => true

        "analysis contains var-usages"
        (contains? (:analysis analysis) :var-usages) => true))))

(specification "build-symbol-graph" :group2
  (behavior "builds symbol graph from analysis"
    (let [analysis (analyzer/run-analysis {:paths ["src/main"]})
          graph    (analyzer/build-symbol-graph analysis)]

      (assertions
        "returns a map"
        (map? graph) => true

        "contains nodes"
        (contains? graph :nodes) => true

        "contains edges"
        (contains? graph :edges) => true

        "contains files"
        (contains? graph :files) => true

        "nodes is a map"
        (map? (:nodes graph)) => true

        "edges is a vector"
        (vector? (:edges graph)) => true

        "files is a map"
        (map? (:files graph)) => true)

      (assertions
        "finds our namespaces"
        (some #(= 'com.fulcrologic.test-filter.analyzer (:symbol %)) (vals (:nodes graph))) => true
        (some #(= 'com.fulcrologic.test-filter.graph (:symbol %)) (vals (:nodes graph))) => true
        (some #(= 'com.fulcrologic.test-filter.git (:symbol %)) (vals (:nodes graph))) => true))))

(specification "find-test-vars"
  (behavior "finds test vars in symbol graph"
    (let [analysis  (analyzer/run-analysis {:paths ["src/test"]})
          graph     (analyzer/build-symbol-graph analysis)
          test-vars (analyzer/find-test-vars graph)]

      (assertions
        "finds test vars from this namespace"
        (some (fn [[sym _node]]
                (= (namespace sym) "com.fulcrologic.test-filter.analyzer-test"))
          test-vars) => true))))

(specification "build-alias-map"
  (behavior "extracts namespace aliases from clj-kondo analysis"
    (let [analysis  (analyzer/run-analysis {:paths ["src/demo/com/fulcrologic/test_filter/integration_demo_test.clj"]})
          alias-map (#'analyzer/build-alias-map analysis "src/demo/com/fulcrologic/test_filter/integration_demo_test.clj")]

      (assertions
        "returns a map"
        (map? alias-map) => true

        "contains the demo alias"
        (contains? alias-map 'demo) => true

        "maps demo to full namespace"
        (get alias-map 'demo) => 'com.fulcrologic.test-filter.integration-demo))))

(specification "resolve-aliased-symbol"
  (let [alias-map {'demo 'com.fulcrologic.test-filter.integration-demo
                   'str  'clojure.string}]

    (behavior "resolves aliased symbols"
      (assertions
        "resolves demo/foo"
        (#'analyzer/resolve-aliased-symbol 'demo/validate-payment alias-map)
        => 'com.fulcrologic.test-filter.integration-demo/validate-payment

        "resolves str/join"
        (#'analyzer/resolve-aliased-symbol 'str/join alias-map)
        => 'clojure.string/join))

    (behavior "leaves non-aliased symbols unchanged"
      (assertions
        "fully-qualified symbols unchanged"
        (#'analyzer/resolve-aliased-symbol 'com.foo/bar alias-map)
        => 'com.foo/bar

        "unknown alias unchanged"
        (#'analyzer/resolve-aliased-symbol 'unknown/foo alias-map)
        => 'unknown/foo

        "plain symbols unchanged"
        (#'analyzer/resolve-aliased-symbol 'foo alias-map)
        => 'foo))))

(specification "extract-test-metadata"
  (behavior "extracts test-targets from metadata"
    (assertions
      "extracts single symbol from :test-targets"
      (:test-targets (#'analyzer/extract-test-metadata
                       {:meta {:test-targets 'foo/bar}}))
      => #{'foo/bar}

      "extracts single symbol from :test-target"
      (:test-targets (#'analyzer/extract-test-metadata
                       {:meta {:test-target 'foo/bar}}))
      => #{'foo/bar}

      "extracts set of symbols"
      (:test-targets (#'analyzer/extract-test-metadata
                       {:meta {:test-targets #{'foo/bar 'baz/qux}}}))
      => #{'foo/bar 'baz/qux}

      "extracts vector of symbols"
      (:test-targets (#'analyzer/extract-test-metadata
                       {:meta {:test-targets ['foo/bar 'baz/qux]}}))
      => #{'foo/bar 'baz/qux}))

  (behavior "detects integration tests"
    (assertions
      "from :integration metadata"
      (:integration? (#'analyzer/extract-test-metadata
                       {:meta {:integration true}}))
      => true

      "from namespace pattern"
      (:integration? (#'analyzer/extract-test-metadata
                       {:ns 'my.app.integration.cache-test}))
      => true

      "returns false for regular tests"
      (:integration? (#'analyzer/extract-test-metadata
                       {:ns   'my.app.core-test
                        :meta {}}))
      => nil)))

(specification "metadata extraction integration"
  (behavior "extracts metadata from integration demo tests"
    (let [analysis   (analyzer/run-analysis {:paths ["src/demo"]})
          graph      (analyzer/build-symbol-graph analysis)
          demo-tests (filter #(clojure.string/includes? (str (:symbol %)) "integration-demo-test")
                       (vals (:nodes graph)))]

      (assertions
        "finds integration demo tests"
        (> (count demo-tests) 0) => true)

      (let [syntax-quoted-test    (first (filter #(clojure.string/includes?
                                                    (get-in % [:metadata :test-name])
                                                    "Syntax Quoted")
                                           demo-tests))
            plain-symbol-test     (first (filter #(clojure.string/includes?
                                                    (get-in % [:metadata :test-name])
                                                    "Plain Symbol")
                                           demo-tests))
            multiple-targets-test (first (filter #(clojure.string/includes?
                                                    (get-in % [:metadata :test-name])
                                                    "Multiple Targets")
                                           demo-tests))
            singular-test         (first (filter #(clojure.string/includes?
                                                    (get-in % [:metadata :test-name])
                                                    "Singular Metadata")
                                           demo-tests))]

        (assertions
          "syntax-quoted test has resolved alias"
          (get-in syntax-quoted-test [:metadata :test-targets])
          => #{(symbol "com.fulcrologic.test-filter.integration-demo" "validate-payment")}

          "plain symbol test has full namespace"
          (contains? (get-in plain-symbol-test [:metadata :test-targets])
            (symbol "com.fulcrologic.test-filter.integration-demo" "process-order"))
          => true

          "multiple targets test has multiple symbols"
          (count (get-in multiple-targets-test [:metadata :test-targets]))
          => 2

          "singular metadata works"
          (get-in singular-test [:metadata :test-targets])
          => #{(symbol "com.fulcrologic.test-filter.integration-demo" "validate-payment")})))))
