(ns com.fulcrologic.test-filter.core-test
  "Unit tests for core test selection logic."
  (:require [clojure.string :as str]
            [com.fulcrologic.test-filter.analyzer :as analyzer]
            [com.fulcrologic.test-filter.cache :as cache]
            [com.fulcrologic.test-filter.content :as content]
            [com.fulcrologic.test-filter.core :as core]
            [fulcro-spec.core :refer [=> assertions behavior specification]]))

;; -----------------------------------------------------------------------------
;; Test Data
;; -----------------------------------------------------------------------------

(def mock-symbol-graph
  "A mock symbol graph for testing."
  {:nodes          {'app.core/handler           {:symbol 'app.core/handler :type :var :file "src/app/core.clj" :line 1 :end-line 3}
                    'app.core/process           {:symbol 'app.core/process :type :var :file "src/app/core.clj" :line 5 :end-line 7}
                    'app.db/query               {:symbol 'app.db/query :type :var :file "src/app/db.clj" :line 1 :end-line 3}
                    'app.core-test/handler-test {:symbol     'app.core-test/handler-test
                                                 :type       :test
                                                 :file       "test/app/core_test.clj"
                                                 :line       1
                                                 :end-line   5
                                                 :defined-by 'clojure.test/deftest
                                                 :metadata   {:test true}}
                    'app.db-test/query-test     {:symbol     'app.db-test/query-test
                                                 :type       :test
                                                 :file       "test/app/db_test.clj"
                                                 :line       1
                                                 :end-line   5
                                                 :defined-by 'clojure.test/deftest
                                                 :metadata   {:test true}}}
   :edges          [{:from 'app.core/handler :to 'app.core/process}
                    {:from 'app.core/handler :to 'app.db/query}
                    {:from 'app.core-test/handler-test :to 'app.core/handler}
                    {:from 'app.db-test/query-test :to 'app.db/query}]
   :paths          ["src" "test"]
   :content-hashes {'app.core/handler           "hash1"
                    'app.core/process           "hash2"
                    'app.db/query               "hash3"
                    'app.core-test/handler-test "test-hash1"
                    'app.db-test/query-test     "test-hash2"}})

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(specification "format-test-output"
  (let [test-symbols ['app.core-test/foo-test
                      'app.core-test/bar-test
                      'app.db-test/query-test]]

    (behavior "formats as vars (default)"
      (let [result (core/format-test-output test-symbols :vars)]

        (assertions
          "returns sorted list of symbols"
          (sequential? result) => true
          (first result) => 'app.core-test/bar-test
          (last result) => 'app.db-test/query-test)))

    (behavior "formats as namespaces"
      (let [result (core/format-test-output test-symbols :namespaces)]

        (assertions
          "returns unique sorted namespaces"
          (sequential? result) => true
          (count result) => 2
          (first result) => "app.core-test"
          (second result) => "app.db-test")))

    (behavior "formats for kaocha"
      (let [result (core/format-test-output test-symbols :kaocha)]

        (assertions
          "returns kaocha focus arguments"
          (string? result) => true
          (str/includes? result "--focus app.core-test") => true
          (str/includes? result "--focus app.db-test") => true)))))

(specification "print-tests"
  (behavior "prints test output to stdout"
    (let [test-symbols ['app.core-test/foo-test]
          output       (with-out-str (core/print-tests test-symbols))]

      (assertions
        "outputs the test symbol"
        (str/includes? output "app.core-test/foo-test") => true)))

  (behavior "respects format option"
    (let [test-symbols ['app.core-test/foo-test 'app.db-test/bar-test]
          output       (with-out-str (core/print-tests test-symbols :format :namespaces))]

      (assertions
        "outputs namespaces"
        (str/includes? output "app.core-test") => true
        (str/includes? output "app.db-test") => true))))

(specification "select-tests with mocked dependencies"
  (behavior "returns all tests when all-tests is true"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly {})]
      (let [result (core/select-tests :all-tests true)]

        (assertions
          "returns selection object"
          (map? result) => true

          "includes all test symbols"
          (contains? (set (:tests result)) 'app.core-test/handler-test) => true
          (contains? (set (:tests result)) 'app.db-test/query-test) => true

          "marks as all-tests selection"
          (:selection-reason (:stats result)) => "all-tests requested"

          "has empty changed symbols"
          (:changed-symbols result) => #{}))))

  (behavior "detects changed symbols by comparing hashes"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly {'app.core/handler "hash1"
                                                        'app.core/process "hash2-OLD"
                                                        'app.db/query     "hash3"})]
      (let [result (core/select-tests)]

        (assertions
          "identifies changed symbol"
          (contains? (:changed-symbols result) 'app.core/process) => true

          "includes hash for changed symbol"
          (contains? (:changed-hashes result) 'app.core/process) => true
          (get (:changed-hashes result) 'app.core/process) => "hash2"

          "does not mark unchanged symbols"
          (contains? (:changed-symbols result) 'app.core/handler) => false))))

  (behavior "detects new symbols (no verified hash)"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly {'app.core/handler "hash1"})]
      (let [result (core/select-tests)]

        (assertions
          "marks symbols without verified hash as changed"
          (contains? (:changed-symbols result) 'app.core/process) => true
          (contains? (:changed-symbols result) 'app.db/query) => true))))

  (behavior "selects tests affected by changes"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly {'app.core/handler           "hash1"
                                                        'app.core/process           "hash2"
                                                        'app.db/query               "hash3-OLD"
                                                        'app.core-test/handler-test "test-hash1"
                                                        'app.db-test/query-test     "test-hash2"})]
      (let [result (core/select-tests)]

        (assertions
          "identifies changed symbol"
          (contains? (:changed-symbols result) 'app.db/query) => true

          "selects test that directly depends on changed symbol"
          (contains? (set (:tests result)) 'app.db-test/query-test) => true

          "also selects test that transitively depends on it"
          (contains? (set (:tests result)) 'app.core-test/handler-test) => true))))

  (behavior "returns empty test list when nothing changed"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly (:content-hashes mock-symbol-graph))]
      (let [result (core/select-tests)]

        (assertions
          "no changed symbols"
          (:changed-symbols result) => #{}

          "no tests selected"
          (:tests result) => []

          "stats show no selection"
          (:selected-tests (:stats result)) => 0))))

  (behavior "uses cached paths when no paths provided"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly {})]
      (let [result (core/select-tests)]

        (assertions
          "uses paths from cache"
          (get-in result [:graph :paths]) => ["src" "test"]))))

  (behavior "includes comprehensive stats"
    (with-redefs [cache/load-graph         (constantly mock-symbol-graph)
                  cache/load-success-cache (constantly {})]
      (let [result (core/select-tests)
            stats  (:stats result)]

        (assertions
          "includes total tests"
          (:total-tests stats) => 2

          "includes selected tests"
          (number? (:selected-tests stats)) => true

          "includes changed symbols count"
          (number? (:changed-symbols stats)) => true

          "includes selection rate"
          (string? (:selection-rate stats)) => true)))))

(specification "mark-verified!"
  (behavior "marks all changed symbols as verified by default"
    (with-redefs [cache/update-success-cache! (fn [hashes] hashes)]
      (let [selection {:changed-symbols #{'app.core/foo 'app.core/bar}
                       :changed-hashes  {'app.core/foo "hash1" 'app.core/bar "hash2"}
                       :graph           mock-symbol-graph}
            result    (core/mark-verified! selection)]

        (assertions
          "marks all symbols as verified"
          (:verified-symbols result) => #{'app.core/foo 'app.core/bar}

          "no skipped symbols"
          (:skipped-symbols result) => #{}))))

  (behavior "marks all changed symbols when :all specified"
    (with-redefs [cache/update-success-cache! (constantly nil)]
      (let [selection {:changed-symbols #{'app.core/foo}
                       :changed-hashes  {'app.core/foo "hash1"}
                       :graph           mock-symbol-graph}
            result    (core/mark-verified! selection :all)]

        (assertions
          "marks all as verified"
          (:verified-symbols result) => #{'app.core/foo}))))

  (behavior "supports partial verification with specific tests"
    (with-redefs [cache/update-success-cache! (constantly nil)]
      (let [selection {:changed-symbols #{'app.core/handler 'app.db/query}
                       :changed-hashes  {'app.core/handler "hash1" 'app.db/query "hash2"}
                       :graph           mock-symbol-graph}
            ;; Only run handler-test, not query-test
            result    (core/mark-verified! selection ['app.core-test/handler-test])]

        (assertions
          "marks handler as verified"
          (contains? (:verified-symbols result) 'app.core/handler) => true

          "also marks query as verified since handler-test covers it transitively"
          (contains? (:verified-symbols result) 'app.db/query) => true

          "no skipped symbols in this case"
          (:skipped-symbols result) => #{}))))

  (behavior "throws on invalid tests-run parameter"
    (let [selection {:changed-symbols #{} :changed-hashes {} :graph mock-symbol-graph}]
      (assertions
        "throws for invalid input"
        (try
          (core/mark-verified! selection "invalid")
          false
          (catch Exception e
            true)) => true))))

(specification "analyze! integration"
  (behavior "performs full analysis and saves cache"
    (let [analyzed (atom false)
          saved    (atom false)]
      (with-redefs [analyzer/run-analysis       (fn [_]
                                                  (reset! analyzed true)
                                                  {:analysis {:var-definitions [] :var-usages []}})
                    analyzer/build-symbol-graph (fn [_] mock-symbol-graph)
                    content/hash-graph-symbols  (fn [_] {'app.core/foo "hash1"})
                    cache/save-graph!           (fn [graph hashes paths reverse]
                                                  (reset! saved true)
                                                  nil)]
        (let [result (core/analyze! :paths ["src"] :verbose false)]

          (assertions
            "runs analysis"
            @analyzed => true

            "saves to cache"
            @saved => true

            "returns graph with hashes"
            (map? result) => true
            (contains? result :content-hashes) => true))))))
