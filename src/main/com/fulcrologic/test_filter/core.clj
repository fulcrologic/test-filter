(ns com.fulcrologic.test-filter.core
  "Main entry point for test selection based on source code changes."
  (:require [clojure.string :as str]
            [com.fulcrologic.test-filter.analyzer :as analyzer]
            [com.fulcrologic.test-filter.cache :as cache]
            [com.fulcrologic.test-filter.content :as content]
            [com.fulcrologic.test-filter.git :as git]
            [com.fulcrologic.test-filter.graph :as graph]))

(defn select-tests
  "Selects tests that need to run based on code changes.

  Options:
  - :from-revision - Compare against specific git revision (partial SHA, branch, tag, or ref like HEAD~3)
  - :to-revision - Compare to specific revision (partial SHA, branch, tag, ref, or nil for working directory)
  - :paths - Paths to analyze (default: uses cached paths, or [\"src\"] if no cache)
  - :force - Force full re-analysis (default: false)
  - :all-tests - Return all tests regardless of changes (default: false)
  - :verbose - Print diagnostic information (default: false)

  Smart revision detection (when from/to not specified):
  - If uncommitted changes exist: compare HEAD to working directory
  - If no uncommitted changes: compare HEAD^ to HEAD

  Revision references support:
  - Full SHA: \"dfd50cb754237161ec6ee4b86f7bb35a21ad4565\"
  - Partial SHA: \"dfd50cb\" (will be resolved to full SHA)
  - Branch name: \"main\", \"feature-branch\"
  - Tag: \"v1.0.0\"
  - Relative refs: \"HEAD^\", \"HEAD~3\", \"main~5\"

  Returns:
  {:tests [test-symbols]
   :changed-symbols [changed-symbols]
   :graph symbol-graph
   :cache-hit? boolean
   :stats {...}}"
  [& {:keys [from-revision to-revision paths force all-tests verbose]
      :or {force false
           all-tests false
           verbose false}}]

  ;; Determine paths: use provided paths, or cached paths, or default to ["src"]
  (let [cached-graph (when-not force (cache/load-graph))
        effective-paths (or paths
                            (:paths cached-graph)
                            ["src"])]

    (when verbose
      (println "=== Test Selection ===")
      (println "Paths:" effective-paths)
      (when (and (nil? paths) (:paths cached-graph))
        (println "  (using cached paths)"))
      (println "Force rebuild:" force))

    ;; Get or build the symbol graph
    (let [symbol-graph (cache/get-or-build-graph :force force :paths effective-paths)
          cache-hit? (and (not force) (cache/cache-valid? cached-graph))

          _ (when verbose
              (println "Cache hit:" cache-hit?)
              (println "Graph nodes:" (count (:nodes symbol-graph)))
              (println "Graph edges:" (count (:edges symbol-graph))))

          ;; Build dependency graph
          dep-graph (graph/build-dependency-graph symbol-graph)

          ;; Find all test vars - get pairs of [symbol node-data]
          test-pairs (vec (analyzer/find-test-vars symbol-graph))
          test-symbols (map first test-pairs)

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

        ;; Determine changed symbols using content hash comparison
        (let [;; Smart revision detection
              has-uncommitted? (git/has-uncommitted-changes?)
              current-head (git/current-revision)

              ;; Resolve user-provided revisions or use smart defaults
              ;; If uncommitted changes: compare HEAD to working dir
              ;; If no uncommitted changes: compare HEAD^ to HEAD
              from-rev-raw (or from-revision
                               (if has-uncommitted?
                                 current-head
                                 "HEAD^"))
              to-rev-raw (or to-revision
                             (if has-uncommitted?
                               nil ; nil means working directory
                               current-head))

              ;; Resolve revision references to full SHAs (except nil for working dir)
              from-rev (if (string? from-rev-raw)
                         (git/resolve-revision from-rev-raw)
                         from-rev-raw)
              to-rev (if (string? to-rev-raw)
                       (git/resolve-revision to-rev-raw)
                       to-rev-raw)

              _ (when verbose
                  (println "Uncommitted changes detected:" has-uncommitted?)
                  (when from-revision
                    (println "User-specified from-revision:" from-revision "resolved to:" from-rev))
                  (when to-revision
                    (println "User-specified to-revision:" to-revision "resolved to:" (or to-rev "working directory")))
                  (println "Comparing revisions:")
                  (println "  From:" from-rev)
                  (println "  To:" (or to-rev "working directory")))

              ;; Get list of changed files from git
              changed-files (git/changed-files from-rev to-rev)

              _ (when verbose
                  (println "Changed files:" (count changed-files))
                  (when (seq changed-files)
                    (doseq [file (take 10 changed-files)]
                      (println "  -" file))
                    (when (> (count changed-files) 10)
                      (println "  ... and" (- (count changed-files) 10) "more"))))

              ;; Re-analyze changed files to get "live" symbol data
              changed-analysis (if (seq changed-files)
                                 (analyzer/run-analysis {:paths (vec changed-files)})
                                 {:analysis nil})
              changed-graph (if (seq changed-files)
                              (analyzer/build-symbol-graph changed-analysis)
                              {:nodes {} :edges [] :files {}})

              ;; Generate content hashes for re-analyzed symbols
              new-hashes (if (seq changed-files)
                           (content/hash-graph-symbols changed-graph)
                           {})

              ;; Get cached content hashes
              old-hashes (or (:content-hashes symbol-graph) {})

              ;; Compare hashes to find truly changed symbols
              changed-symbols (content/find-changed-symbols old-hashes new-hashes)

              _ (when verbose
                  (println "Symbols with content changes:" (count changed-symbols))
                  (when (seq changed-symbols)
                    (doseq [sym (take 10 changed-symbols)]
                      (println "  -" sym))
                    (when (> (count changed-symbols) 10)
                      (println "  ... and" (- (count changed-symbols) 10) "more"))))

              ;; Find affected tests
              affected-tests (if (empty? changed-symbols)
                               []
                               (graph/find-affected-tests dep-graph test-pairs changed-symbols symbol-graph))

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
                                     "N/A")}})))))

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
