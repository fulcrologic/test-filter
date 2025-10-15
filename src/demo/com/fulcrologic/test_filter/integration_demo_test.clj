(ns com.fulcrologic.test-filter.integration-demo-test
  "Integration tests demonstrating metadata-based test targeting."
  (:require
    [com.fulcrologic.test-filter.integration-demo :as demo]
    [fulcro-spec.core :refer [assertions specification]]))

;; Test 1: Using plain fully-qualified symbol
(specification {:test-targets com.fulcrologic.test-filter.integration-demo/process-order}
  "Order Processing - Plain Symbol"
  (assertions
    "processes orders correctly"
    (demo/process-order {:id 123 :total 100}) => {:status   :processed
                                                  :order-id 123
                                                  :total    100}))

;; Test 2: Using syntax-quoted symbol with alias
(specification {:test-targets `demo/validate-payment}
  "Payment Validation - Syntax Quoted"
  (assertions
    "validates valid payment"
    (demo/validate-payment {:card-number "1234"
                            :expiry      "12/25"
                            :cvv         "123"
                            :amount      50}) => true
    "rejects invalid payment"
    (demo/validate-payment {:card-number "1234"
                            :expiry      "12/25"
                            :cvv         "123"
                            :amount      0}) => false))

;; Test 3: Using multiple targets as a set
(specification {:test-targets #{com.fulcrologic.test-filter.integration-demo/send-notification
                                com.fulcrologic.test-filter.integration-demo/handle-refund}}
  "Notification and Refund - Multiple Targets"
  (assertions
    "sends notification"
    (demo/send-notification "customer-1" "Order shipped") => {:sent-to "customer-1"
                                                              :message "Order shipped"}
    "handles refund"
    (demo/handle-refund 456 25.50) => {:refunded 456
                                       :amount   25.50}))

;; Test 4: Using test-target (singular) instead of test-targets
(specification {:test-target com.fulcrologic.test-filter.integration-demo/validate-payment}
  "Payment Validation - Singular Metadata"
  (assertions
    "validates payment structure"
    (demo/validate-payment {:card-number nil
                            :expiry      "12/25"
                            :cvv         "123"
                            :amount      50}) => false))
