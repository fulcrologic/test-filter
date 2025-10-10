(ns com.fulcrologic.test-filter.core
  "Main entry point for test selection based on source code changes."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [com.fulcrologic.test-filter.analyzer :as analyzer]
            [com.fulcrologic.test-filter.cache :as cache]
            [com.fulcrologic.test-filter.content :as content]
            [com.fulcrologic.test-filter.graph :as graph]))

(defn analyze!
  "Analyzes the codebase and updates the analysis cache.

  This always does a full analysis and completely overwrites the analysis cache.
  It does NOT update the success cache - use mark-verified! for that.

  Options:
  - :paths - Paths to analyze (default: [\"src\"])
  - :verbose - Print diagnostic information (default: true)

  Returns the built symbol graph with :content-hashes."
  [& {:keys [paths verbose]
      :or   {paths   ["src"]
             verbose true}}]

  (when verbose
    (println "=== Analyzing Codebase ===")
    (println "Paths:" paths))

  (let [start-time     (System/currentTimeMillis)

        ;; Always do full analysis
        analysis       (analyzer/run-analysis {:paths paths})
        graph          (analyzer/build-symbol-graph analysis)
        content-hashes (content/hash-graph-symbols graph)

        ;; Save to analysis cache
        _              (cache/save-graph! graph content-hashes paths)

        elapsed        (- (System/currentTimeMillis) start-time)
        stats          (graph/graph-stats (graph/build-dependency-graph graph))
        test-count     (count (analyzer/find-test-vars graph))]

    (when verbose
      (println "\n=== Analysis Complete ===")
      (println "Time elapsed:" (format "%.2fs" (/ elapsed 1000.0)))
      (println "Total symbols:" (:node-count stats))
      (println "Dependencies:" (:edge-count stats))
      (println "Test symbols:" test-count)
      (println "Analysis cache saved to:" (cache/cache-path)))

    (assoc graph :content-hashes content-hashes)))

(defn select-tests
  "Selects tests that need to run by comparing current state vs verified baseline.

  Options:
  - :paths - Paths to analyze (default: uses cached paths, or [\"src\"] if no cache)
  - :all-tests - Return all tests regardless of changes (default: false)
  - :verbose - Print diagnostic information (default: false)

  Returns a selection object:
  {:tests [test-symbols]              ; Tests that should run
   :changed-symbols #{...}            ; Symbols that changed
   :changed-hashes {symbol -> hash}   ; New hashes for changed symbols
   :graph symbol-graph                ; Full dependency graph
   :stats {...}}"
  [& {:keys [paths all-tests verbose]
      :or   {all-tests false
             verbose   false}}]

  ;; Determine paths: use provided paths, or cached paths, or default to ["src"]
  (let [cached-graph    (cache/load-graph)
        effective-paths (or paths
                          (:paths cached-graph)
                          ["src"])]

    (when verbose
      (println "=== Test Selection ===")
      (println "Paths:" effective-paths)
      (when (and (nil? paths) (:paths cached-graph))
        (println "  (using cached paths)")))

    ;; Load or build the analysis cache
    (let [symbol-graph   (or cached-graph
                           (do
                             (when verbose
                               (println "No analysis cache found, running analyze..."))
                             (analyze! :paths effective-paths :verbose verbose)))

          current-hashes (or (:content-hashes symbol-graph) {})

          _              (when verbose
                           (println "Graph nodes:" (count (:nodes symbol-graph)))
                           (println "Graph edges:" (count (:edges symbol-graph))))

          ;; Build dependency graph
          dep-graph      (graph/build-dependency-graph symbol-graph)

          ;; Find all test vars - get pairs of [symbol node-data]
          test-pairs     (vec (analyzer/find-test-vars symbol-graph))
          test-symbols   (map first test-pairs)

          _              (when verbose
                           (println "Total tests found:" (count test-symbols)))]

      (if all-tests
        ;; Return all tests
        {:tests           test-symbols
         :changed-symbols #{}
         :changed-hashes  {}
         :graph           symbol-graph
         :stats           {:total-tests      (count test-symbols)
                           :selected-tests   (count test-symbols)
                           :changed-symbols  0
                           :selection-reason "all-tests requested"}}

        ;; Compare current hashes vs success cache
        (let [success-hashes  (cache/load-success-cache)

              _               (when verbose
                                (println "Success cache entries:" (count success-hashes)))

              ;; Find symbols where current hash differs from verified hash
              changed-symbols (into #{}
                                (keep (fn [[sym current-hash]]
                                        (let [verified-hash (get success-hashes sym)]
                                          ;; Changed if: no verified hash OR hash differs
                                          (when (or (nil? verified-hash)
                                                  (not= current-hash verified-hash))
                                            sym)))
                                  current-hashes))

              ;; Build map of changed symbol -> new hash
              changed-hashes  (into {}
                                (keep (fn [sym]
                                        (when-let [hash (get current-hashes sym)]
                                          [sym hash]))
                                  changed-symbols))

              _               (when verbose
                                (println "Symbols with changes:" (count changed-symbols))
                                (when (seq changed-symbols)
                                  (doseq [sym (take 10 changed-symbols)]
                                    (println "  -" sym))
                                  (when (> (count changed-symbols) 10)
                                    (println "  ... and" (- (count changed-symbols) 10) "more"))))

              ;; Find affected tests
              affected-tests  (if (empty? changed-symbols)
                                []
                                (graph/find-affected-tests dep-graph test-pairs changed-symbols symbol-graph))

              _               (when verbose
                                (println "Affected tests:" (count affected-tests)))]

          {:tests           affected-tests
           :changed-symbols changed-symbols
           :changed-hashes  changed-hashes
           :graph           symbol-graph
           :stats           {:total-tests     (count test-symbols)
                             :selected-tests  (count affected-tests)
                             :changed-symbols (count changed-symbols)
                             :selection-rate  (if (pos? (count test-symbols))
                                                (format "%.1f%%"
                                                  (* 100.0 (/ (count affected-tests)
                                                             (count test-symbols))))
                                                "N/A")}})))))

(defn mark-verified!
  "Marks tests as successfully verified by updating the success cache.

  This should only be called after tests have passed. It updates the success
  cache with the content hashes from the selection, creating a new baseline
  for future test selection.

  Args:
  - selection: The selection object returned by select-tests
  - tests-run: (optional) Either :all, nil, or a vector of specific test symbols

  If tests-run is nil or :all (default):
    - Updates success cache with ALL changed-hashes from selection

  If tests-run is a vector of specific tests:
    - Performs reverse graph walk to find which changed symbols are covered
      by those tests
    - Only updates hashes for covered symbols
    - Other symbols remain unverified (will still trigger tests)

  Returns:
  {:verified-symbols #{...}  ; Symbols that were marked as verified
   :skipped-symbols #{...}   ; Symbols not covered (if partial verification)}"
  ([selection]
   (mark-verified! selection :all))

  ([selection tests-run]
   (let [changed-hashes (:changed-hashes selection)
         graph          (:graph selection)]

     (cond
       ;; Mark all changed symbols as verified
       (or (nil? tests-run) (= :all tests-run))
       (do
         (cache/update-success-cache! changed-hashes)
         {:verified-symbols (set (keys changed-hashes))
          :skipped-symbols  #{}})

       ;; Partial verification - only mark covered symbols
       (vector? tests-run)
       (let [changed-symbols  (:changed-symbols selection)
             dep-graph        (graph/build-dependency-graph graph)

             ;; Find all symbols covered by the tests that ran
             ;; (Each test's transitive dependencies)
             covered-symbols  (reduce
                                (fn [acc test-sym]
                                  (set/union acc (graph/transitive-dependencies dep-graph test-sym)))
                                #{}
                                tests-run)

             ;; Intersect with changed symbols to find what we can verify
             verified-symbols (set/intersection changed-symbols covered-symbols)
             skipped-symbols  (set/difference changed-symbols verified-symbols)

             ;; Only update hashes for verified symbols
             verified-hashes  (select-keys changed-hashes verified-symbols)]

         (when (seq verified-hashes)
           (cache/update-success-cache! verified-hashes))

         {:verified-symbols verified-symbols
          :skipped-symbols  skipped-symbols})

       :else
       (throw (ex-info "tests-run must be nil, :all, or a vector of test symbols"
                {:tests-run tests-run}))))))

(defn format-test-output
  "Formats selected tests for output.

  Format options:
  - :namespaces - List of unique test namespaces
  - :vars - List of fully-qualified test vars (default)
  - :kaocha - Kaocha test selector format"
  [test-symbols format]
  (case format
    :namespaces
    (->> test-symbols
      (map namespace)
      (distinct)
      (sort))

    :kaocha
    (->> test-symbols
      (map namespace)
      (distinct)
      (map #(str "--focus " %))
      (str/join " "))

    :vars
    (sort test-symbols)

    ;; Default to vars
    (sort test-symbols)))

(defn print-tests
  "Prints selected tests in the specified format.

  Format options:
  - :namespaces - One namespace per line
  - :vars - One fully-qualified var per line (default)
  - :kaocha - Kaocha command-line arguments (single line)"
  [test-symbols & {:keys [format] :or {format :vars}}]
  (let [formatted (format-test-output test-symbols format)]
    (if (string? formatted)
      ;; Kaocha format returns a single string
      (println formatted)
      ;; Other formats return collections
      (doseq [item formatted]
        (println item)))))

(comment
  ;; Example usage

  ;; Analyze codebase and build cache
  (analyze!)

  ;; Select tests based on changes since cache
  (def result (select-tests :verbose true))

  ;; Print affected test namespaces
  (print-tests (:tests result) :format :namespaces)

  ;; Print affected test vars
  (print-tests (:tests result) :format :vars)

  ;; Get stats
  (:stats result)

  ;; Select all tests (ignore changes)
  (select-tests :all-tests true)

  ;; Force rebuild and select tests
  (select-tests :force true)

  ;; Compare specific revisions
  (select-tests :from-revision "HEAD~5" :to-revision "HEAD")

  ;; Kaocha integration
  (print-tests (:tests result) :format :kaocha))
