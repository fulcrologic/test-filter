(ns com.fulcrologic.test-filter.cli
  "Command-line interface for test-filter."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [com.fulcrologic.test-filter.cache :as cache]
            [com.fulcrologic.test-filter.core :as core])
  (:gen-class))

(def cli-options
  [["-p" "--paths PATHS" "Comma-separated list of paths to analyze"
    :default ["src"]
    :parse-fn #(str/split % #",")]

   ["-v" "--verbose" "Print verbose diagnostic information"]

   ["-a" "--all" "For select: return all tests. For clear/mark-verified: apply to all caches/tests"]

   ["-o" "--format FORMAT" "Output format: vars, namespaces, or kaocha"
    :default :vars
    :parse-fn keyword
    :validate [#{:vars :namespaces :kaocha}
               "Must be one of: vars, namespaces, kaocha"]]

   ["-t" "--tests TESTS" "For mark-verified: comma-separated list of test symbols to mark"
    :parse-fn #(map symbol (str/split % #","))]

   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["Test Filter - Intelligent test selection based on source code changes"
        ""
        "Usage: clojure -M:cli [command] [options]"
        ""
        "Commands:"
        "  analyze        Analyze codebase and update analysis cache"
        "  select         Select tests to run based on changes"
        "  mark-verified  Mark tests as verified (updates success cache)"
        "  clear          Clear cache(s)"
        "  status         Show cache status"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  # Analyze codebase and cache results"
        "  clojure -M:cli analyze"
        ""
        "  # Select tests affected by changes since last analysis"
        "  clojure -M:cli select"
        ""
        "  # Select tests with verbose output"
        "  clojure -M:cli select -v"
        ""
        "  # Select all tests regardless of changes"
        "  clojure -M:cli select --all"
        ""
        "  # Output test namespaces (one per line)"
        "  clojure -M:cli select -o namespaces"
        ""
        "  # Output as Kaocha arguments"
        "  clojure -M:cli select -o kaocha"
        ""
        "  # Mark all selected tests as verified"
        "  clojure -M:cli mark-verified"
        ""
        "  # Mark specific tests as verified"
        "  clojure -M:cli mark-verified -t my.app/test1,my.app/test2"
        ""
        "  # Clear analysis cache only"
        "  clojure -M:cli clear"
        ""
        "  # Clear both analysis and success caches"
        "  clojure -M:cli clear --all"
        ""
        "For more information, see: https://github.com/your-org/test-filter"]
    (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
    (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Returns a map with either :action, :options,
  or :errors."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:action :help :summary summary}

      errors
      {:action :error :errors errors}

      (empty? arguments)
      {:action :error
       :errors ["No command specified. Use 'analyze', 'select', 'mark-verified', 'clear', or 'status'."]}

      (not (#{"analyze" "select" "mark-verified" "clear" "status"} (first arguments)))
      {:action :error
       :errors [(str "Unknown command: " (first arguments))]}

      :else
      {:action  (keyword (first arguments))
       :options options})))

(defn run-analyze [options]
  (try
    (core/analyze! :paths (:paths options)
      :verbose (or (:verbose options) true))
    0
    (catch Exception e
      (println "Error during analysis:" (.getMessage e))
      (when (:verbose options)
        (.printStackTrace e))
      1)))

(defn run-select [options]
  (try
    (let [result       (core/select-tests
                         :paths (:paths options)
                         :all-tests (:all options)
                         :verbose (:verbose options))
          test-symbols (:tests result)]

      (when (:verbose options)
        (println "\n=== Selection Results ===")
        (println "Total tests:" (get-in result [:stats :total-tests]))
        (println "Selected tests:" (get-in result [:stats :selected-tests]))
        (println "Changed symbols:" (get-in result [:stats :changed-symbols]))
        (println "Selection rate:" (get-in result [:stats :selection-rate]))
        (println))

      (if (empty? test-symbols)
        (when (:verbose options)
          (println "No tests need to be run."))
        (core/print-tests test-symbols :format (:format options)))

      0)
    (catch Exception e
      (println "Error during test selection:" (.getMessage e))
      (when (:verbose options)
        (.printStackTrace e))
      1)))

(defn run-clear [options]
  (try
    (if (:all options)
      ;; Clear both caches
      (do
        (cache/invalidate-all-caches!)
        (println "Both analysis and success caches cleared."))
      ;; Clear only analysis cache
      (if (cache/invalidate-cache!)
        (println "Analysis cache cleared.")
        (println "No analysis cache found.")))
    0
    (catch Exception e
      (println "Error clearing cache:" (.getMessage e))
      1)))

(defn run-mark-verified [options]
  (try
    ;; Need to get the last selection result
    ;; For CLI, we'll re-run select-tests to get the selection
    (let [selection       (core/select-tests
                            :paths (:paths options)
                            :verbose (:verbose options))
          test-symbols    (:tests selection)
          changed-symbols (:changed-symbols selection)]

      (if (empty? changed-symbols)
        (do
          (println "No changed symbols to verify.")
          0)
        (let [tests-to-mark (cond
                              ;; User specified specific tests
                              (:tests options)
                              (vec (:tests options))

                              ;; Mark all by default
                              :else
                              :all)

              result        (core/mark-verified! selection tests-to-mark)]

          (println "=== Verification Complete ===")
          (println "Verified symbols:" (count (:verified-symbols result)))
          (when (seq (:skipped-symbols result))
            (println "Skipped symbols:" (count (:skipped-symbols result)))
            (println "  (not fully covered by specified tests)"))

          (when (:verbose options)
            (println "\nVerified symbols:")
            (doseq [sym (:verified-symbols result)]
              (println "  ✓" sym))
            (when (seq (:skipped-symbols result))
              (println "\nSkipped symbols:")
              (doseq [sym (:skipped-symbols result)]
                (println "  -" sym))))

          0)))
    (catch Exception e
      (println "Error marking tests as verified:" (.getMessage e))
      (when (:verbose options)
        (.printStackTrace e))
      1)))

(defn run-status [options]
  (try
    (let [status         (cache/cache-status)
          analysis-cache (:analysis-cache status)
          success-cache  (:success-cache status)]

      (println "=== Cache Status ===")
      (println)
      (println "Analysis Cache:")
      (println "  Path:" (:path analysis-cache))
      (if (:exists? analysis-cache)
        (do
          (println "  Status: ✓ Exists")
          (println "  Size:" (:size analysis-cache) "bytes")
          (println "  Last modified:" (:last-modified analysis-cache)))
        (println "  Status: ✗ Not found"))

      (println)
      (println "Success Cache:")
      (println "  Path:" (:path success-cache))
      (if (:exists? success-cache)
        (do
          (println "  Status: ✓ Exists")
          (println "  Size:" (:size success-cache) "bytes")
          (println "  Last modified:" (:last-modified success-cache))
          (when (:verbose options)
            (let [verified (cache/load-success-cache)]
              (println "  Verified symbols:" (count verified)))))
        (println "  Status: ✗ Not found"))

      (when (and (:exists? analysis-cache) (:verbose options))
        (let [cached (cache/load-graph)]
          (println)
          (println "Analysis Cache Details:")
          (println "  Analyzed at:" (:analyzed-at cached))
          (println "  Paths:" (:paths cached))
          (println "  Total symbols:" (count (:nodes cached)))
          (println "  Dependencies:" (count (:edges cached)))
          (println "  Files analyzed:" (count (:files cached)))))

      0)
    (catch Exception e
      (println "Error checking cache status:" (.getMessage e))
      (when (:verbose options)
        (.printStackTrace e))
      1)))

(defn -main [& args]
  (let [{:keys [action options errors summary]} (validate-args args)]
    (case action
      :help
      (do (println (usage summary))
          (System/exit 0))

      :error
      (do (println (error-msg errors))
          (println)
          (println (usage summary))
          (System/exit 1))

      :analyze
      (System/exit (run-analyze options))

      :select
      (System/exit (run-select options))

      :mark-verified
      (System/exit (run-mark-verified options))

      :clear
      (System/exit (run-clear options))

      :status
      (System/exit (run-status options))

      ;; Default
      (do (println "Unknown action:" action)
          (System/exit 1)))))

(comment
  ;; Test CLI parsing
  (validate-args ["analyze"])
  (validate-args ["select" "-v"])
  (validate-args ["select" "--format" "namespaces"])
  (validate-args ["--help"])

  ;; Test commands
  (-main "analyze")
  (-main "select" "-v")
  (-main "status")
  (-main "clear"))
