(ns com.fulcrologic.test-filter.scenario
  "Test scenario for why? and why-not? verification.")

(defn base-function
  "A base function that will be changed."
  [x]
  (* x 2))

(defn covered-caller
  "This function calls base-function and HAS test coverage."
  [x]
  (inc (base-function x)))

(defn uncovered-caller
  "This function calls base-function but has NO test coverage."
  [x]
  (dec (base-function x)))
