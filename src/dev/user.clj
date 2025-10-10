(ns user
  (:require
    [com.fulcrologic.test-filter.core :as tf]
    [kaocha.repl :as k]))


(defn run-tests! []
  (let [{:keys [tests]} (tf/select-tests)]
    (apply k/run tests)))

(comment
  ;; Example usage

  ;; Analyze codebase and build cache
  (tf/analyze! :paths ["src/main" "src/demo" "src/test"])

  ;; Select tests based on changes since cache
  (def result (tf/select-tests :verbose true))

  ;; Print affected test namespaces
  (tf/print-tests (:tests result) :format :namespaces)

  ;; Print affected test vars
  (tf/print-tests (:tests result) :format :vars)

  ;; Get stats
  (:stats result)

  ;; Select all tests (ignore changes)
  (tf/select-tests :all-tests true)

  ;; Force rebuild and select tests
  (tf/select-tests :force true)

  ;; Compare specific revisions
  (tf/select-tests :from-revision "HEAD~5" :to-revision "HEAD")

  ;; Kaocha integration
  (tf/print-tests (:tests result) :format :kaocha))
