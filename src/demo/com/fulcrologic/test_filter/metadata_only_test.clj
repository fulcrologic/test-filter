(ns com.fulcrologic.test-filter.metadata-only-test
  "Test to verify that :test-targets metadata works WITHOUT actual function calls.

  IMPORTANT: This test demonstrates that :test-targets works INDEPENDENTLY of clj-kondo's
  usage tracking. You don't need to actually call the target functions - the metadata alone
  is sufficient for test selection. This is useful for integration tests where dependencies
  might be dynamic, indirect, or not statically analyzable."
  (:require
    [com.fulcrologic.test-filter.integration-demo :as demo]
    [fulcro-spec.core :refer [assertions specification]]))

;; Test 1: Metadata-only targeting (no function call)
;; This test will run when validate-payment changes, even though it never calls that function.
;; This proves that :test-targets works independently of actual usage.
(specification {:test-targets `demo/validate-payment}
  "Test with metadata but NO function call"
  (assertions
    "this test doesn't actually call validate-payment"
    (+ 1 1) => 2
    "it just has the metadata"
    true => true
    "but it will still run when validate-payment changes"
    (= 2 2) => true))

;; Test 2: Metadata targeting different from actual calls
;; This test targets send-notification but actually calls process-order.
;; It will run when send-notification changes (due to metadata), not when process-order changes.
(specification {:test-targets #{com.fulcrologic.test-filter.integration-demo/send-notification}}
  "Test targeting send-notification but calling process-order"
  (assertions
    "calls process-order, but that's NOT what triggers this test"
    (demo/process-order {:id 999 :total 50}) => {:status   :processed
                                                 :order-id 999
                                                 :total    50}
    "this test runs when send-notification changes, not process-order"
    true => true))
