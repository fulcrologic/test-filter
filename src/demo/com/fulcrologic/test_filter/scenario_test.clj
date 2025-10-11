(ns com.fulcrologic.test-filter.scenario-test
  "Tests for the scenario namespace."
  (:require
    [com.fulcrologic.test-filter.scenario :as scenario]
    [fulcro-spec.core :refer [assertions specification]]))

(specification "base-function"
  (assertions
    "doubles the input"
    (scenario/base-function 5) => 10))

(specification "covered-caller"
  (assertions
    "calls base-function and adds 1"
    (scenario/covered-caller 5) => 11))

;; Note: uncovered-caller has NO test - this is intentional
