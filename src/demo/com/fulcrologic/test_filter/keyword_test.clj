(ns com.fulcrologic.test-filter.keyword-test
  "Test file with keyword arguments to specification."
  (:require
    [com.fulcrologic.test-filter.scenario :as scenario]
    [fulcro-spec.core :refer [assertions specification]]))

(declare =>)

(specification "test-with-keyword" :group1
  (assertions
    "works"
    (scenario/base-function 5) => 10))

(specification "test-with-metadata" {:focus true}
  (assertions
    "also works"
    (scenario/base-function 3) => 6))

(specification "test-with-both" {:meta true} :group2
  (assertions
    "works too"
    (scenario/covered-caller 5) => 11))
