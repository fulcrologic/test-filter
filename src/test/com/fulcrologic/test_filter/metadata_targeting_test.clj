(ns com.fulcrologic.test-filter.metadata-targeting-test
  "Tests to verify that :test-targets metadata works independently of actual function calls."
  (:require
    [com.fulcrologic.test-filter.analyzer :as analyzer]
    [com.fulcrologic.test-filter.graph :as graph]
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "Metadata-only test targeting"
  (let [analysis   (analyzer/run-analysis {:paths ["src/demo"]})
        sg         (analyzer/build-symbol-graph analysis)
        loom-graph (graph/build-dependency-graph sg)
        test-vars  (analyzer/find-test-vars sg)]

    (assertions
      "finds metadata-only test"
      (some #(= 'com.fulcrologic.test-filter.metadata-only-test/__Test-with-metadata-but-NO-function-call__ (first %))
        test-vars)
      => true)

    (let [;; Test 1: validate-payment changes
          changed-validate    #{'com.fulcrologic.test-filter.integration-demo/validate-payment}
          affected-validate   (graph/find-affected-tests
                                loom-graph
                                test-vars
                                changed-validate
                                sg)
          metadata-only-tests (filter #(clojure.string/includes? (str %) "metadata-only-test")
                                affected-validate)]

      (assertions
        "selects metadata-only test when validate-payment changes"
        (some #(clojure.string/includes? (str %) "metadata-but-NO-function-call")
          metadata-only-tests)
        => true))

    (let [;; Test 2: send-notification changes
          changed-notify      #{'com.fulcrologic.test-filter.integration-demo/send-notification}
          affected-notify     (graph/find-affected-tests
                                loom-graph
                                test-vars
                                changed-notify
                                sg)
          metadata-only-tests (filter #(clojure.string/includes? (str %) "metadata-only-test")
                                affected-notify)]

      (assertions
        "selects test targeting send-notification even though it calls process-order"
        (some #(clojure.string/includes? (str %) "targeting-send-notification")
          metadata-only-tests)
        => true))

    (let [;; Test 3: process-order changes (called but not targeted)
          changed-process  #{'com.fulcrologic.test-filter.integration-demo/process-order}
          affected-process (graph/find-affected-tests
                             loom-graph
                             test-vars
                             changed-process
                             sg)
          notify-test      (filter #(clojure.string/includes? (str %) "targeting-send-notification")
                             affected-process)]

      (assertions
        "does NOT select test when process-order changes (it calls it but doesn't target it)"
        (empty? notify-test)
        => true))))
