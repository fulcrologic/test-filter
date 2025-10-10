(ns user
  (:require
    [com.fulcrologic.test-filter.core :as tf]
    [kaocha.repl :as k]))

(defn run-tests! []
  (let [{:keys [tests]} (tf/select-tests)]
    (apply k/run tests)))

(comment
  (k/run-all)
  ;; Example usage
  (run-tests!)

  ;; Analyze codebase and build cache
  (tf/analyze! :paths ["src/demo"] :force true)

  ;; Select tests based on changes since cache
  (def result (tf/select-tests :verbose true
                :paths ["src/demo"]))

  ;; Print affected test namespaces
  (tf/print-tests (:tests result) :format :namespaces)

  ;; Print affected test vars
  (tf/print-tests (:tests result) :format :vars)

  ;; Compare specific revisions
  (tf/select-tests :from-revision "HEAD~5" :to-revision "HEAD")

  ;; Kaocha integration
  (tf/print-tests (:tests result) :format :kaocha))
