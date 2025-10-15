(ns com.fulcrologic.test-filter.kaocha-plugin
  "Kaocha plugin for test-filter integration.

  This plugin integrates test-filter's selective test execution with kaocha.
  It supports two modes:

  1. Bootstrap mode (TEST_FILTER_BOOTSTRAP=true):
     - Runs all tests without filtering
     - Marks all symbols as verified after successful test run
     - Used when no success cache exists yet

  2. Normal mode (TEST_FILTER_BOOTSTRAP=false or not set):
     - Uses test-filter to select only affected tests
     - Marks selected tests as verified after successful run
     - Requires both .test-filter-cache.edn and .test-filter-success.edn

  Usage:
    Add :com.fulcrologic.test-filter/kaocha-plugin to your tests.edn plugins list
    Set TEST_FILTER_BOOTSTRAP environment variable as needed"
  (:require
    [clojure.string :as str]
    [kaocha.plugin :refer [defplugin]]
    [com.fulcrologic.test-filter.core :as tf]
    [com.fulcrologic.test-filter.cache :as cache]))

(defn- bootstrap-mode?
  "Check if we're in bootstrap mode (should run all tests and create initial cache)."
  []
  (= "true" (System/getenv "TEST_FILTER_BOOTSTRAP")))

(defn- get-test-var-symbol
  "Extract the fully-qualified test var symbol from a kaocha test map.

  Kaocha test maps have structure:
  {:kaocha.testable/id 'namespace/test-name
   :kaocha.testable/type :kaocha.type/var
   ...}

  Returns the symbol (e.g., 'my.ns/my-test) or nil if not a test var."
  [test-map]
  (when (= :kaocha.type/var (:kaocha.testable/type test-map))
    (:kaocha.testable/id test-map)))

(defn- filter-tests-by-selection
  "Filter kaocha test suite to include only selected tests.

  Args:
    test-suite - Kaocha test suite (nested structure of testables)
    selected-test-symbols - Set of test var symbols that should run

  Returns:
    Filtered test suite with only selected tests"
  [test-suite selected-test-symbols]
  (let [selected-set (set selected-test-symbols)]
    (letfn [(filter-testable [testable]
              (let [test-var-sym (get-test-var-symbol testable)]
                (cond
                  ;; This is a test var - keep only if selected
                  test-var-sym
                  (when (contains? selected-set test-var-sym)
                    testable)

                  ;; This is a container (ns, suite, etc) - recursively filter children
                  (contains? testable :kaocha.test-plan/tests)
                  (let [filtered-children (keep filter-testable (:kaocha.test-plan/tests testable))]
                    (when (seq filtered-children)
                      (assoc testable :kaocha.test-plan/tests filtered-children)))

                  ;; Unknown testable type - keep it
                  :else
                  testable)))]

      (filter-testable test-suite))))

(defplugin com.fulcrologic.test-filter/kaocha-plugin
  (pre-test [test-plan]
    (if (bootstrap-mode?)
      ;; Bootstrap mode - no filtering, just log
      (do
        (println "\n=== Test-Filter: Bootstrap Mode ===")
        (println "Running all tests to establish baseline cache")
        (println "Will mark all symbols as verified after successful run\n")
        (assoc test-plan ::mode :bootstrap))

      ;; Normal mode - filter tests based on selection
      (try
        (println "\n=== Test-Filter: Selective Testing Mode ===")

        ;; Check if caches exist
        (let [cache-path   (cache/cache-path)
              success-path (cache/success-cache-path)]

          (when-not (.exists (java.io.File. cache-path))
            (throw (ex-info "Analysis cache not found. Run test-filter analyze first."
                     {:cache-path cache-path})))

          (when-not (.exists (java.io.File. success-path))
            (println "WARNING: Success cache not found at" success-path)
            (println "Set TEST_FILTER_BOOTSTRAP=true to create initial cache")
            (throw (ex-info "Success cache not found. Set TEST_FILTER_BOOTSTRAP=true to bootstrap."
                     {:success-path success-path}))))

        ;; Select tests using test-filter
        (let [selection         (tf/select-tests :verbose true)
              selected-tests    (:tests selection)
              stats             (:stats selection)]

          (println "\n=== Test Selection Results ===")
          (println "Total tests:" (:total-tests stats))
          (println "Selected tests:" (:selected-tests stats))
          (println "Changed symbols:" (:changed-symbols stats))
          (println "Selection rate:" (:selection-rate stats))
          (println "Reason:" (:selection-reason stats "changes detected"))

          (if (empty? selected-tests)
            (do
              (println "\nNo tests need to run - no changes detected!")
              ;; Return plan with no tests
              (assoc test-plan
                :kaocha.test-plan/tests []
                ::mode :filtered
                ::selection selection))

            (do
              (println "\nFiltering test suite to run selected tests...\n")
              ;; Filter the test plan to only include selected tests
              (let [filtered-suite (filter-tests-by-selection test-plan (set selected-tests))]
                (assoc filtered-suite
                  ::mode :filtered
                  ::selection selection)))))

        (catch Exception e
          (println "\n=== Test-Filter Error ===")
          (println "Failed to select tests:" (.getMessage e))
          (println "Falling back to running all tests\n")
          ;; On error, fall back to running all tests
          (assoc test-plan ::mode :error ::error e)))))

  (post-run [test-result]
    (let [mode      (::mode test-result)
          all-pass? (zero? (+ (:kaocha.result/fail test-result 0)
                             (:kaocha.result/error test-result 0)))]

      (cond
        ;; Tests failed - don't update cache
        (not all-pass?)
        (do
          (println "\n=== Test-Filter: Tests Failed ===")
          (println "Success cache NOT updated (tests must pass to verify changes)")
          test-result)

        ;; Bootstrap mode - mark all verified
        (= mode :bootstrap)
        (do
          (println "\n=== Test-Filter: Bootstrap Complete ===")
          (println "All tests passed! Marking all symbols as verified...")
          (try
            ;; Load the graph from cache (which should exist from analyze step)
            (let [graph (cache/load-graph)]
              (if graph
                (let [verified-count (tf/mark-all-verified! graph)]
                  (println "Marked" verified-count "symbols as verified")
                  (println "Success cache saved to:" (cache/success-cache-path))
                  (println "Future test runs will only execute affected tests"))
                (println "WARNING: Could not load analysis cache to mark symbols as verified")))
            (catch Exception e
              (println "ERROR marking symbols as verified:" (.getMessage e))))
          test-result)

        ;; Normal mode - mark selected tests as verified
        (= mode :filtered)
        (do
          (println "\n=== Test-Filter: Tests Passed ===")
          (println "Marking selected changes as verified...")
          (try
            (let [selection (::selection test-result)]
              (if selection
                (let [result (tf/mark-verified! selection)]
                  (println "Verified" (count (:verified-symbols result)) "symbols")
                  (when (seq (:skipped-symbols result))
                    (println "Skipped" (count (:skipped-symbols result)) "symbols (not covered by selected tests)"))
                  (println "Success cache updated"))
                (println "WARNING: No selection data available to mark as verified")))
            (catch Exception e
              (println "ERROR marking symbols as verified:" (.getMessage e))))
          test-result)

        ;; Error mode - don't update cache
        (= mode :error)
        (do
          (println "\n=== Test-Filter: Error Mode ===")
          (println "Tests passed but test-filter had errors during selection")
          (println "Success cache NOT updated")
          test-result)

        ;; Unknown mode
        :else
        (do
          (println "\n=== Test-Filter: Unknown Mode ===")
          (println "Unexpected mode:" mode)
          test-result)))))
