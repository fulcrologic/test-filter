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
        "finds this test namespace"
        (some (fn [[sym _node]]
                (= sym 'com.fulcrologic.test-filter.analyzer-test))
          test-vars) => true))))
