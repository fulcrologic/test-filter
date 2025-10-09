(ns test-filter.core
  "Main entry point for test selection based on source code changes."
  (:require [test-filter.analyzer :as analyzer]
            [test-filter.graph :as graph]
            [test-filter.git :as git]
            [test-filter.cache :as cache]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn select-tests
  "Selects tests that need to run based on code changes.
  
  Options:
  - :from-revision - Compare against specific git revision (default: smart detection)
  - :to-revision - Compare to specific revision (default: smart detection)
  - :paths - Paths to analyze (default: [\"src\"])
  - :force - Force full re-analysis (default: false)
  - :all-tests - Return all tests regardless of changes (default: false)
  - :verbose - Print diagnostic information (default: false)
  
  Smart revision detection (when from/to not specified):
  - If uncommitted changes exist: compare HEAD to working directory
  - If no uncommitted changes: compare HEAD^ to HEAD
  
  Returns:
  {:tests [test-symbols]
   :changed-symbols [changed-symbols]
   :graph symbol-graph
   :cache-hit? boolean
   :stats {...}}"
  [& {:keys [from-revision to-revision paths force all-tests verbose]
      :or {paths ["src"]
           force false
           all-tests false
           verbose false}}]

  (when verbose
    (println "=== Test Selection ===")
    (println "Paths:" paths)
    (println "Force rebuild:" force))

  ;; Get or build the symbol graph
  (let [symbol-graph (cache/get-or-build-graph :force force :paths paths)
        cache-hit? (and (not force) (cache/cache-valid? (cache/load-graph)))

        _ (when verbose
            (println "Cache hit:" cache-hit?)
            (println "Graph nodes:" (count (:nodes symbol-graph)))
            (println "Graph edges:" (count (:edges symbol-graph))))

        ;; Build dependency graph
        dep-graph (graph/build-dependency-graph symbol-graph)

        ;; Find all test symbols - extract just the symbols (keys) from MapEntry objects
        test-symbols (map first (analyzer/find-test-vars symbol-graph))

        _ (when verbose
            (println "Total tests found:" (count test-symbols)))]

    (if all-tests
      ;; Return all tests
      {:tests test-symbols
       :changed-symbols []
       :graph symbol-graph
       :cache-hit? cache-hit?
       :stats {:total-tests (count test-symbols)
               :selected-tests (count test-symbols)
               :changed-symbols 0
               :selection-reason "all-tests requested"}}

      ;; Determine changed symbols with smart revision detection
      (let [;; Smart revision detection
            has-uncommitted? (git/has-uncommitted-changes?)
            current-head (git/current-revision)

            ;; If uncommitted changes: compare HEAD to working dir
            ;; If no uncommitted changes: compare HEAD^ to HEAD
            from-rev (or from-revision
                         (if has-uncommitted?
                           current-head
                           "HEAD^"))
            to-rev (or to-revision
                       (if has-uncommitted?
                         nil ; nil means working directory
                         current-head))

            _ (when verbose
                (println "Uncommitted changes detected:" has-uncommitted?)
                (println "Comparing revisions:")
                (println "  From:" from-rev)
                (println "  To:" (or to-rev "working directory")))

            ;; Find changed symbols
            changed-symbols (git/find-changed-symbols symbol-graph from-rev to-rev)

            _ (when verbose
                (println "Changed symbols:" (count changed-symbols))
                (when (seq changed-symbols)
                  (doseq [sym (take 10 changed-symbols)]
                    (println "  -" sym))
                  (when (> (count changed-symbols) 10)
                    (println "  ... and" (- (count changed-symbols) 10) "more"))))

            ;; Find affected tests
            affected-tests (if (empty? changed-symbols)
                             []
                             (graph/find-affected-tests dep-graph test-symbols changed-symbols))

            _ (when verbose
                (println "Affected tests:" (count affected-tests)))]

        {:tests affected-tests
         :changed-symbols changed-symbols
         :graph symbol-graph
         :cache-hit? cache-hit?
         :stats {:total-tests (count test-symbols)
                 :selected-tests (count affected-tests)
                 :changed-symbols (count changed-symbols)
                 :selection-rate (if (pos? (count test-symbols))
                                   (format "%.1f%%"
                                           (* 100.0 (/ (count affected-tests)
                                                       (count test-symbols))))
                                   "N/A")}}))))

(defn analyze!
  "Analyzes the codebase and builds/updates the cache.
  
  Options:
  - :paths - Paths to analyze (default: [\"src\"])
  - :force - Force full rebuild (default: false)
  - :verbose - Print diagnostic information (default: true)
  
  Returns the built symbol graph."
  [& {:keys [paths force verbose]
      :or {paths ["src"]
           force false
           verbose true}}]

  (when verbose
    (println "=== Analyzing Codebase ===")
    (println "Paths:" paths)
    (println "Force rebuild:" force))

  (let [start-time (System/currentTimeMillis)
        graph (cache/get-or-build-graph :force force :paths paths)
        elapsed (- (System/currentTimeMillis) start-time)

        stats (graph/graph-stats (graph/build-dependency-graph graph))
        test-count (count (analyzer/find-test-vars graph))]

    (when verbose
      (println "\n=== Analysis Complete ===")
      (println "Time elapsed:" (format "%.2fs" (/ elapsed 1000.0)))
      (println "Revision:" (:revision graph))
      (println "Total symbols:" (:node-count stats))
      (println "Dependencies:" (:edge-count stats))
      (println "Test symbols:" test-count)
      (println "Cache saved to:" (cache/cache-path)))

    graph))

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
