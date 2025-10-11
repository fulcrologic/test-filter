(ns com.fulcrologic.test-filter.core
  "Main entry point for test selection based on source code changes."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.test-filter.analyzer :as analyzer]
    [com.fulcrologic.test-filter.cache :as cache]
    [com.fulcrologic.test-filter.content :as content]
    [com.fulcrologic.test-filter.git :as git]
    [com.fulcrologic.test-filter.graph :as graph]
    [taoensso.tufte :refer [p]]))

(defn analyze!
  "Analyzes the codebase and updates the analysis cache.

  This always does a full analysis and completely overwrites the analysis cache.
  It does NOT update the success cache - use mark-verified! for that.

  Options:
  - :paths - Paths to analyze (default: [\"src\"])
  - :verbose - Print diagnostic information (default: true)
  - :save? - (default true) save the analysis to an on-disk cache

  Returns the built symbol graph with :content-hashes, :reverse-index, and :dep-graph."
  [& {:keys [paths verbose save?]
      :or   {paths   ["src"]
             save?   true
             verbose true}}]

  (when verbose
    (println "=== Analyzing Codebase ===")
    (println "Paths:" paths))

  (p `analyze!
    (let [start-time     (System/currentTimeMillis)

          ;; Always do full analysis
          analysis       (analyzer/run-analysis {:paths paths})
          graph          (analyzer/build-symbol-graph analysis)
          content-hashes (content/hash-graph-symbols graph)

          ;; Build dependency graph (cache in memory for reuse)
          _              (when verbose
                           (println "Building dependency graph..."))
          dep-graph      (graph/build-dependency-graph graph)

          ;; Build reverse index for optimized test selection
          _              (when verbose
                           (println "Building reverse dependency index..."))
          idx-start      (System/currentTimeMillis)
          reverse-index  (graph/symbols-with-dependents dep-graph)
          idx-time       (- (System/currentTimeMillis) idx-start)

          _              (when verbose
                           (println "Reverse index built in" (format "%.2fs" (/ idx-time 1000.0)))
                           (println "Index entries:" (count reverse-index)))

          ;; Save to analysis cache with reverse index (dep-graph not saved - can't serialize)
          _              (when save?
                           (cache/save-graph! graph content-hashes paths reverse-index))

          elapsed        (- (System/currentTimeMillis) start-time)
          stats          (graph/graph-stats dep-graph)
          test-count     (count (analyzer/find-test-vars graph))]

      (when verbose
        (println "\n=== Analysis Complete ===")
        (println "Time elapsed:" (format "%.2fs" (/ elapsed 1000.0)))
        (println "Total symbols:" (:node-count stats))
        (println "Dependencies:" (:edge-count stats))
        (println "Test symbols:" test-count)
        (println "Analysis cache saved to:" (cache/cache-path)))

      (assoc graph
        :content-hashes content-hashes
        :reverse-index reverse-index
        :dep-graph dep-graph))))

(defn patch-graph-with-local-changes
  "Updates a graph with fresh content hashes for uncommitted file changes.

  This is optimized for fast iteration - instead of re-analyzing the entire codebase,
  it only re-hashes symbols in files that have uncommitted changes according to git.

  This enables a fast REPL workflow:
  1. Run analyze! once to get the base graph
  2. Edit code and save files
  3. Call patch-graph-with-local-changes to update only changed symbols
  4. Select and run tests
  5. Repeat from step 2

  The patched graph can be passed directly to select-tests via :graph parameter,
  avoiding any I/O (no cache reads/writes).

  Args:
    graph - The symbol graph from analyze! or load-graph
    verbose - Print diagnostic information (default: false)

  Returns:
    Updated graph with fresh content hashes for changed files.
    Preserves :dep-graph if present for performance.

  Example:
    (def graph (analyze!))
    ;; Edit some files...
    (def selection (select-tests :graph (patch-graph-with-local-changes graph)))
    (apply k/run (:tests selection))"
  [graph & {:keys [verbose]
            :or   {verbose false}}]

  (let [;; Find files with uncommitted changes
        changed-files (try
                        (git/uncommitted-files)
                        (catch Exception e
                          (when verbose
                            (println "Warning: Could not detect uncommitted files:" (.getMessage e))
                            (println "Returning graph unchanged."))
                          #{}))

        _             (when verbose
                        (println "=== Patching Graph with Local Changes ===")
                        (println "Uncommitted files:" (count changed-files))
                        (when (seq changed-files)
                          (doseq [f (take 5 changed-files)]
                            (println "  -" f))
                          (when (> (count changed-files) 5)
                            (println "  ... and" (- (count changed-files) 5) "more"))))]

    (if (empty? changed-files)
      ;; No changes, return graph as-is
      (do
        (when verbose
          (println "No uncommitted changes detected."))
        graph)

      ;; Re-hash the changed files
      (let [old-hashes  (:content-hashes graph)
            live-hashes (content/rehash-files graph changed-files)
            new-hashes  (merge old-hashes live-hashes)

            _           (when verbose
                          (println "Re-hashed symbols:" (count live-hashes))
                          (println "Total hashes:" (count new-hashes)))]

        ;; Preserve :dep-graph if present (no need to rebuild)
        (assoc graph :content-hashes new-hashes)))))

(defn select-tests
  "Selects tests that need to run by comparing current state vs verified baseline.

  Options:
  - :graph - Use provided graph instead of loading from cache (default: nil)
  - :paths - Paths to analyze if no graph/cache (default: [\"src\"])
  - :all-tests - Return all tests regardless of changes (default: false)
  - :verbose - Print diagnostic information (default: false)

  Returns a selection object:
  {:tests [test-symbols]              ; Tests that should run
   :changed-symbols #{...}            ; Symbols that changed
   :changed-hashes {symbol -> hash}   ; New hashes for changed symbols
   :untested-usages {sym -> #{...}}   ; Direct dependents with no test coverage
   :trace (delay {...})               ; Lazy dependency chains from tests to changed symbols
   :graph symbol-graph                ; Full dependency graph
   :stats {...}}

  Note: :trace is a delay that computes dependency chains only when dereferenced.
  Use @(:trace selection) or (force (:trace selection)) to access it."
  [& {:keys [graph paths all-tests verbose]
      :or   {all-tests false
             verbose   false}}]

  (when verbose
    (println "=== Test Selection ==="))

  ;; OPTIMIZATION: Check success cache early - if empty, return all tests without analysis
  (let [success-hashes (cache/load-success-cache)]
    (if (and (empty? success-hashes) (not all-tests))
      ;; No success cache = first run or cache cleared, return all tests
      (let [symbol-graph (or graph
                           (cache/load-graph)
                           (do
                             (when verbose
                               (println "No success cache found - first run or cache cleared")
                               (println "Running analyze to find all tests..."))
                             (analyze! :paths (or paths ["src"]) :verbose verbose)))
            test-pairs   (vec (analyzer/find-test-vars symbol-graph))
            test-symbols (map first test-pairs)]

        (when verbose
          (println "Success cache is empty, returning all" (count test-symbols) "tests"))

        {:tests           test-symbols
         :changed-symbols #{}
         :changed-hashes  {}
         :untested-usages {}
         :trace           (delay {})
         :graph           symbol-graph
         :stats           {:total-tests      (count test-symbols)
                           :selected-tests   (count test-symbols)
                           :changed-symbols  0
                           :untested-usages  0
                           :selection-rate   "100.0%"
                           :selection-reason "no success cache (first run)"}})

      ;; Normal path: compare current vs success cache
      (p `select-tests
        (let [symbol-graph   (or graph
                               (cache/load-graph)
                               (do
                                 (when verbose
                                   (println "No graph provided or cached, running analyze..."))
                                 (analyze! :paths (or paths ["src"]) :verbose verbose)))

              _              (when verbose
                               (if graph
                                 (println "Using provided graph")
                                 (println "Loaded graph from cache"))
                               (println "Graph nodes:" (count (:nodes symbol-graph)))
                               (println "Graph edges:" (count (:edges symbol-graph))))

              current-hashes (or (:content-hashes symbol-graph) {})
              reverse-index  (:reverse-index symbol-graph)

              ;; OPTIMIZATION: Use cached dep-graph if available (from analyze! or previous call)
              dep-graph      (or (:dep-graph symbol-graph)
                               (do
                                 (when verbose
                                   (println "Building dependency graph..."))
                                 (graph/build-dependency-graph symbol-graph)))

              ;; Prepare graph parameter with reverse index if available
              graph-param    (if reverse-index
                               {:graph dep-graph :reverse-index reverse-index}
                               dep-graph)

              _              (when (and verbose reverse-index)
                               (println "Using cached reverse dependency index"))

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
             :untested-usages {}
             :trace           (delay {})
             :graph           symbol-graph
             :stats           {:total-tests      (count test-symbols)
                               :selected-tests   (count test-symbols)
                               :changed-symbols  0
                               :untested-usages  0
                               :selection-rate   "100.0%"
                               :selection-reason "all-tests requested"}}

            ;; Compare current hashes vs success cache
            (let [_               (when verbose
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

                  ;; Find affected tests (use graph-param which includes reverse index if available)
                  affected-tests  (if (empty? changed-symbols)
                                    []
                                    (graph/find-affected-tests graph-param test-pairs changed-symbols symbol-graph))

                  _               (when verbose
                                    (println "Affected tests:" (count affected-tests)))

                  ;; OPTIMIZATION: Defer trace computation using delay - only compute when accessed
                  ;; This saves ~71% of select-tests time when trace is not needed (most cases)
                  trace-delay     (delay
                                    (if (seq affected-tests)
                                      (graph/trace-test-dependencies dep-graph affected-tests changed-symbols)
                                      {}))

                  ;; Find untested usages
                  untested-usages (if (empty? changed-symbols)
                                    {}
                                    (graph/find-untested-usages graph-param changed-symbols symbol-graph))

                  _               (when (and verbose (seq untested-usages))
                                    (println "Found untested usages for" (count untested-usages) "changed symbols"))]

              {:tests           affected-tests
               :changed-symbols changed-symbols
               :changed-hashes  changed-hashes
               :untested-usages untested-usages
               :trace           trace-delay
               :graph           symbol-graph
               :stats           {:total-tests     (count test-symbols)
                                 :selected-tests  (count affected-tests)
                                 :changed-symbols (count changed-symbols)
                                 :untested-usages (reduce + (map count (vals untested-usages)))
                                 :selection-rate  (if (pos? (count test-symbols))
                                                    (format "%.1f%%"
                                                      (* 100.0 (/ (count affected-tests)
                                                                 (count test-symbols))))
                                                    "N/A")}})))))))

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

(defn mark-all-verified!
  "Marks all symbols in the graph as verified, creating an initial baseline.

  This is useful for initializing test-filter on an existing large codebase.
  After calling this, only new changes will trigger tests.

  Args:
  - graph: Symbol graph from analyze! (with :content-hashes)

  Returns the number of symbols marked as verified."
  [graph]
  (let [content-hashes (:content-hashes graph)]
    (when (empty? content-hashes)
      (throw (ex-info "No content hashes found in graph"
               {:graph-keys (keys graph)})))
    (cache/save-success-cache! content-hashes)
    (count content-hashes)))

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

(defn why?
  "Explains why tests were selected in human-readable form.

  Takes the selection object from select-tests and prints an outline showing
  each test and the dependency chain(s) that caused it to be selected.

  Format:
  test-symbol
    Because f changed.

  or with a chain:
  test-symbol
    Because the test uses a which calls b, c. And c changed.

  Args:
    selection - The selection object returned by select-tests

  Example:
    (def result (select-tests :verbose true))
    (why? result)"
  [selection]
  (let [tests           (:tests selection)
        changed-symbols (:changed-symbols selection)
        ;; Force the delay to compute trace if needed
        trace           (force (:trace selection))]

    (when (empty? tests)
      (println "No tests need to run - no changes detected."))

    (doseq [test-sym tests]
      (println test-sym)
      (if-let [traces (get trace test-sym)]
        (if (empty? traces)
          ;; No trace info for this test
          (println "  Because it was selected (the test itself probably changed).")
          ;; We have trace information showing paths to changed symbols
          (let [;; Group paths by their changed endpoint
                paths-by-changed (into (sorted-map)
                                   (for [[changed-sym path] traces]
                                     [changed-sym (vec (rest path))]))

                ;; Build description of each path
                descriptions     (for [[changed-sym path] paths-by-changed
                                       :let [intermediate (butlast path)]]
                                   (if (empty? intermediate)
                                     ;; Direct dependency - just the changed symbol
                                     (str "  " changed-sym " (changed)")
                                     ;; Has intermediate steps
                                     (str "  "
                                       (str/join "\n  " intermediate)
                                       "\n  " changed-sym " (changed)")))]
            (println (str (str/join "" descriptions)))))
        ;; No trace info at all - shouldn't happen but handle gracefully
        (println "  Because it was selected (the test itself probably changed).")))
    (println)))

(defn why-not?
  "Shows which direct usages of changed symbols are NOT covered by tests.

  This helps identify coverage gaps: functions that depend on changed code
  but have no tests exercising them. These represent potential risk areas
  where changes could cause problems without any tests catching them.

  Format:
  changed-symbol
    Direct usages with no test coverage:
      untested-symbol-1
      untested-symbol-2

  Args:
    selection - The selection object returned by select-tests

  Example:
    (def result (select-tests :verbose true))
    (why-not? result)"
  [selection]
  (let [untested-usages (:untested-usages selection)]

    (if (empty? untested-usages)
      (println "All direct usages of changed symbols are covered by tests!")
      (do
        (println "=== Coverage Gaps: Untested Usages ===\n")
        (doseq [[changed-sym untested-symbols] (sort-by (comp str first) untested-usages)]
          (println changed-sym)
          (println "  Direct usages with no test coverage:")
          (doseq [untested-sym (sort untested-symbols)]
            (println "   " untested-sym))
          (println))))))

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

  ;; Use a provided graph instead of cache
  (def graph (analyze! :paths ["src"]))
  (select-tests :graph graph)

  ;; Fast iteration with patch-graph-with-local-changes
  (def graph (analyze! :paths ["src"]))
  (def selection (select-tests :graph (patch-graph-with-local-changes graph)))

  ;; Explain why tests were selected
  (why? result)

  ;; Kaocha integration
  (print-tests (:tests result) :format :kaocha))
