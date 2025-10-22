(ns com.fulcrologic.test-filter.kaocha-plugin
  "Kaocha plugin for test-filter integration.

  This plugin integrates test-filter's selective test execution with kaocha.
  It supports three modes controlled by TEST_FILTER_MODE environment variable:

  1. Bootstrap mode (TEST_FILTER_MODE=bootstrap):
     - Runs all tests without filtering
     - Marks all symbols as verified after successful test run
     - Used when no success cache exists yet

  2. Filter mode (TEST_FILTER_MODE=filter):
     - Uses test-filter to select only affected tests
     - Marks selected tests as verified after successful run
     - Requires both .test-filter-cache.edn and .test-filter-success.edn

  3. No-op mode (TEST_FILTER_MODE not set or any other value):
     - Plugin passes through without any filtering or caching
     - Behaves as if the plugin is not installed

  Usage:
    Add :com.fulcrologic.test-filter/kaocha-plugin to your tests.edn plugins list
    Set TEST_FILTER_MODE environment variable as needed"
  (:require
   [com.fulcrologic.test-filter.cache :as cache]
   [com.fulcrologic.test-filter.core :as tf]
   [kaocha.plugin :refer [defplugin]]))

(defn- get-filter-mode
  "Get the test filter mode from TEST_FILTER_MODE environment variable.

  Returns:
  - :bootstrap - Run all tests and mark everything as verified
  - :filter - Filter tests based on changes
  - :noop - Pass through without any filtering (default)"
  []
  (case (System/getenv "TEST_FILTER_MODE")
    "bootstrap" :bootstrap
    "filter" :filter
    :noop))

;; Atom to store selection data and track if we've already processed
;; Multiple test suites in tests.edn would cause pre-test to run multiple times
;; We only want to do selection once per test run
(defonce ^:private plugin-state (atom {:selection nil
                                       :processed? false}))

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
    Filtered test suite with only selected tests (always returns a valid testable)"
  [test-suite selected-test-symbols]
  ;; Convert symbols to keywords since kaocha uses keywords for :kaocha.testable/id
  (let [selected-set (set (map (comp keyword str) selected-test-symbols))]
    (letfn [(filter-testable [testable]
              (let [test-var-id (get-test-var-symbol testable)]
                (cond
                  ;; This is a test var - keep only if selected
                  test-var-id
                  (when (contains? selected-set test-var-id)
                    testable)

                  ;; This is a container (ns, suite, etc) - recursively filter children
                  (contains? testable :kaocha.test-plan/tests)
                  (let [filtered-children (keep filter-testable (:kaocha.test-plan/tests testable))]
                    (when (seq filtered-children)
                      (assoc testable :kaocha.test-plan/tests filtered-children)))

                  ;; Unknown testable type - keep it
                  :else
                  testable)))]

      ;; Always return a valid testable at the top level
      (or (filter-testable test-suite)
          (assoc test-suite :kaocha.test-plan/tests [])))))

(defplugin com.fulcrologic.test-filter/kaocha-plugin
  (pre-test [testable test-plan]
    ;; Only process test suites (type :kaocha.type/clojure.test)
            (if-not (= :kaocha.type/clojure.test (:kaocha.testable/type testable))
              testable ; Pass through non-suite testables unchanged

              (let [mode (get-filter-mode)]
                (case mode
          ;; No-op mode - pass through silently
                  :noop
                  testable

          ;; Bootstrap mode - no filtering, just log once
                  :bootstrap
                  (do
                    (when-not (:processed? @plugin-state)
                      (println "\n=== Test-Filter: Bootstrap Mode ===")
                      (println "Running all tests to establish baseline cache")
                      (println "Will mark all symbols as verified after successful run\n")
                      (flush)
                      (swap! plugin-state assoc :processed? true))
                    testable)

          ;; Filter mode - select once, then filter each suite
                  :filter
                  (try
            ;; Only run selection once for all suites
                    (when-not (:processed? @plugin-state)
                      (println "\n=== Test-Filter: Selective Testing Mode ===")
                      (flush)

              ;; Check if caches exist
                      (let [cache-path (cache/cache-path)
                            success-path (cache/success-cache-path)]

                        (when-not (.exists (java.io.File. cache-path))
                          (throw (ex-info "Analysis cache not found. Run test-filter analyze first."
                                          {:cache-path cache-path})))

                        (when-not (.exists (java.io.File. success-path))
                          (println "WARNING: Success cache not found at" success-path)
                          (println "Set TEST_FILTER_MODE=bootstrap to create initial cache")
                          (flush)
                          (throw (ex-info "Success cache not found. Set TEST_FILTER_MODE=bootstrap to bootstrap."
                                          {:success-path success-path}))))

              ;; Select tests using test-filter
                      (let [selection (tf/select-tests :verbose true)
                            selected-tests (:tests selection)
                            stats (:stats selection)]

                ;; Store selection for filtering and post-run hook
                        (swap! plugin-state assoc
                               :selection selection
                               :processed? true)

                        (println "\n=== Test Selection Results ===")
                        (println "Total tests:" (:total-tests stats))
                        (println "Selected tests:" (:selected-tests stats))
                        (println "Changed symbols:" (:changed-symbols stats))
                        (println "Selection rate:" (:selection-rate stats))
                        (println "Reason:" (:selection-reason stats "changes detected"))
                        (flush)))

            ;; Filter this suite using the cached selection
                    (let [selection (:selection @plugin-state)
                          selected-tests (:tests selection)]

                      (if (empty? selected-tests)
                        (do
                          (when-not (:processed? @plugin-state)
                            (println "\nNo tests need to run - no changes detected!")
                            (flush))
                  ;; Return testable with no tests
                          (assoc testable :kaocha.test-plan/tests []))

                        (do
                  ;; Filter the testable to only include selected tests
                          (filter-tests-by-selection testable (set selected-tests)))))

                    (catch Exception e
                      (when-not (:processed? @plugin-state)
                        (println "\n=== Test-Filter Error ===")
                        (println "Failed to select tests:" (.getMessage e))
                        (println "Falling back to running all tests\n")
                        (flush)
                ;; Mark as processed even on error to prevent repeated error messages
                        (swap! plugin-state assoc :processed? true))
              ;; Clear selection state on error
                      (swap! plugin-state assoc :selection nil)
              ;; On error, fall back to running all tests
                      testable))))))

  (post-run [test-result]
            (let [mode (get-filter-mode) ; Read mode from environment variable
                  all-pass? (zero? (+ (:kaocha.result/fail test-result 0)
                                      (:kaocha.result/error test-result 0)))]

      ;; Reset processed flag for next run
              (swap! plugin-state assoc :processed? false)

              (cond
        ;; No-op mode - pass through silently
                (= mode :noop)
                test-result

        ;; Tests failed - don't update cache
                (not all-pass?)
                (do
                  (println "\n=== Test-Filter: Tests Failed ===")
                  (println "Success cache NOT updated (tests must pass to verify changes)")
                  (flush)
                  test-result)

        ;; Bootstrap mode - mark all verified
                (= mode :bootstrap)
                (do
                  (println "\n=== Test-Filter: Bootstrap Complete ===")
                  (println "All tests passed! Marking all symbols as verified...")
                  (flush)
                  (try
            ;; Analyze and build graph on-the-fly
                    (let [graph (tf/analyze! :paths ["src"])]
                      (if graph
                        (let [verified-count (tf/mark-all-verified! graph)]
                          (println "Marked" verified-count "symbols as verified")
                          (println "Success cache saved to:" (cache/success-cache-path))
                          (println "Future test runs will only execute affected tests")
                          (flush))
                        (do
                          (println "WARNING: Could not analyze codebase to mark symbols as verified")
                          (flush))))
                    (catch Exception e
                      (println "ERROR marking symbols as verified:" (.getMessage e))
                      (flush)))
                  test-result)

        ;; Filter mode - mark selected tests as verified
                (= mode :filter)
                (do
                  (println "\n=== Test-Filter: Tests Passed ===")
                  (println "Marking selected changes as verified...")
                  (flush)
                  (try
                    (let [selection (:selection @plugin-state)]
                      (if selection
                        (let [result (tf/mark-verified! selection)]
                          (println "Verified" (count (:verified-symbols result)) "symbols")
                          (when (seq (:skipped-symbols result))
                            (println "Skipped" (count (:skipped-symbols result)) "symbols (not covered by selected tests)"))
                          (println "Success cache updated")
                          (flush))
                        (do
                          (println "WARNING: No selection data available to mark as verified")
                          (flush))))
                    (catch Exception e
                      (println "ERROR marking symbols as verified:" (.getMessage e))
                      (flush)))
                  test-result)

        ;; Unknown mode (shouldn't happen)
                :else
                test-result))))
