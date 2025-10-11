(ns development
  (:require
    [clj-reload.core :as reload]
    [com.fulcrologic.test-filter.core :as tf]
    [kaocha.repl :as k]
    [taoensso.tufte :as tufte :refer [profile]]))

(defn run-tests!
  "Select and run affected tests using Kaocha."
  []
  (profile {}
    (let [{:keys [tests]} (tf/select-tests)]
      (apply k/run tests))))

(defn verify!
  "Mark the last selection as verified.
  Run this after tests pass to update the success cache."
  []
  (let [selection (tf/select-tests :verbose false)]
    (if (seq (:changed-symbols selection))
      (do
        (tf/mark-verified! selection)
        (println "✓ Marked" (count (:changed-symbols selection)) "symbols as verified"))
      (println "No changed symbols to verify"))))

(comment
  (reload/reload)
  (tufte/add-basic-println-handler! {})
  (profile
    (k/run-all))

  ;; 1. Analyze codebase (creates/updates analysis cache)
  (def graph (tf/analyze! :paths ["src/demo"]))

  ;; 2. Select tests (compares current vs success cache)
  (def selection (tf/select-tests
                   :graph (tf/patch-graph-with-local-changes graph)
                   :verbose true
                   :paths ["src/demo"]))
  (tf/why? selection)
  (tf/mark-verified! selection)

  (some->> (:tests
             (tf/select-tests
               :graph (tf/patch-graph-with-local-changes graph)
               :verbose true
               :paths ["src/demo"]))
    (seq)
    (apply k/run))

  ;; 3. View what needs testing
  (tf/print-tests (:tests selection) :format :namespaces)
  (:stats selection)

  ;; 4. Run the tests
  (apply k/run (:tests selection))
  ;; Or use the helper:
  (run-tests!)

  ;; 5. After tests pass, mark as verified
  (tf/mark-verified! selection)
  ;; Or use the helper:
  (verify!)

  ;; 6. Select again - should show no tests needed
  (tf/select-tests :verbose true :paths ["src/demo"])

  ;; ---
  ;; Other examples

  ;; Select all tests regardless of changes
  (tf/select-tests :all-tests true)

  ;; Mark only specific tests as verified
  (tf/mark-verified! selection ['my.app-test/specific-test])

  ;; View cache status
  (require '[com.fulcrologic.test-filter.cache :as cache])
  (cache/cache-status)

  ;; Clear caches
  (cache/invalidate-cache!)                                 ; Clear analysis cache only
  (cache/invalidate-all-caches!)                            ; Clear both caches

  )
