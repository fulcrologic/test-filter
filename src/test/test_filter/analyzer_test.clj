(ns test-filter.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [test-filter.analyzer :as analyzer]))

(deftest test-run-analysis
  (testing "Analyzer can run clj-kondo and parse results"
    (let [analysis (analyzer/run-analysis {:paths ["src/main"]})]
      (is (map? analysis))
      (is (contains? analysis :analysis))
      (is (contains? (:analysis analysis) :var-definitions))
      (is (contains? (:analysis analysis) :var-usages)))))

(deftest test-build-symbol-graph
  (testing "Building symbol graph from analysis"
    (let [analysis (analyzer/run-analysis {:paths ["src/main"]})
          graph (analyzer/build-symbol-graph analysis)]
      (is (map? graph))
      (is (contains? graph :nodes))
      (is (contains? graph :edges))
      (is (map? (:nodes graph)))
      (is (vector? (:edges graph)))

      ;; Should have found our namespaces
      (is (some #(= 'test-filter.analyzer (:symbol %)) (vals (:nodes graph))))
      (is (some #(= 'test-filter.graph (:symbol %)) (vals (:nodes graph))))
      (is (some #(= 'test-filter.git (:symbol %)) (vals (:nodes graph)))))))

(deftest test-find-test-vars
  (testing "Finding test vars in symbol graph"
    (let [analysis (analyzer/run-analysis {:paths ["src/test"]})
          graph (analyzer/build-symbol-graph analysis)
          test-vars (analyzer/find-test-vars graph)]
      ;; This test itself should be found
      (is (some (fn [[sym _node]]
                  (= sym 'test-filter.analyzer-test/test-run-analysis))
                test-vars)))))
