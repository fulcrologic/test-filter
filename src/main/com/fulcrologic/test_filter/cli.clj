(ns com.fulcrologic.test-filter.cli
  "Command-line interface for test-filter."
  (:require [com.fulcrologic.test-filter.core :as core]
            [com.fulcrologic.test-filter.cache :as cache]
            [clojure.tools.cli :as cli]
            [clojure.string :as str])
  (:gen-class))

(def cli-options
  [["-p" "--paths PATHS" "Comma-separated list of paths to analyze"
    :default ["src"]
    :parse-fn #(str/split % #",")]

   ["-f" "--force" "Force full re-analysis, ignore cache"]

   ["-v" "--verbose" "Print verbose diagnostic information"]

   ["-a" "--all" "Select all tests regardless of changes"]

   ["-o" "--format FORMAT" "Output format: vars, namespaces, or kaocha"
    :default :vars
    :parse-fn keyword
    :validate [#{:vars :namespaces :kaocha}
               "Must be one of: vars, namespaces, kaocha"]]

   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["Test Filter - Intelligent test selection based on source code changes"
        ""
        "Usage: clojure -M -m test-filter.cli [command] [options]"
        ""
        "Commands:"
        "  analyze    Analyze codebase and build/update cache"
        "  select     Select tests to run based on changes"
        "  clear      Clear the analysis cache"
        "  status     Show cache status"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  # Analyze codebase and cache results"
        "  clojure -M -m test-filter.cli analyze"
        ""
        "  # Select tests affected by changes since last analysis"
        "  clojure -M -m test-filter.cli select"
        ""
        "  # Select tests with verbose output"
        "  clojure -M -m test-filter.cli select -v"
        ""
        "  # Force re-analysis and select all tests"
        "  clojure -M -m test-filter.cli select --force --all"
        ""
        "  # Output test namespaces (one per line)"
        "  clojure -M -m test-filter.cli select -o namespaces"
        ""
        "  # Output as Kaocha arguments"
        "  clojure -M -m test-filter.cli select -o kaocha"
        ""
        "  # Clear cache"
        "  clojure -M -m test-filter.cli clear"
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
       :errors ["No command specified. Use 'analyze', 'select', 'clear', or 'status'."]}

      (not (#{"analyze" "select" "clear" "status"} (first arguments)))
      {:action :error
       :errors [(str "Unknown command: " (first arguments))]}

      :else
      {:action (keyword (first arguments))
       :options options})))

(defn run-analyze [options]
  (try
    (core/analyze! :paths (:paths options)
                   :force (:force options)
                   :verbose (or (:verbose options) true))
    0
    (catch Exception e
      (println "Error during analysis:" (.getMessage e))
      (when (:verbose options)
        (.printStackTrace e))
      1)))

(defn run-select [options]
  (try
    (let [result (core/select-tests
                  :paths (:paths options)
                  :force (:force options)
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
    (if (cache/invalidate-cache!)
      (println "Cache cleared successfully.")
      (println "No cache found."))
    0
    (catch Exception e
      (println "Error clearing cache:" (.getMessage e))
      1)))

(defn run-status [options]
  (try
    (let [cached (cache/load-graph)]
      (if cached
        (do
          (println "=== Cache Status ===")
          (println "Cache file:" (cache/cache-path))
          (println "Cached revision:" (:revision cached))
          (println "Analyzed at:" (:analyzed-at cached))
          (println "Total symbols:" (count (:nodes cached)))
          (println "Dependencies:" (count (:edges cached)))
          (println "Files analyzed:" (count (:files cached)))
          (println)
          (if (cache/cache-valid? cached)
            (println "✓ Cache is valid for current revision")
            (let [changed (cache/changed-files-since-cache cached)]
              (println "⚠ Cache is stale")
              (println "Changed files since cache:" (count changed))
              (when (and (:verbose options) (seq changed))
                (doseq [file (take 10 changed)]
                  (println "  -" file))
                (when (> (count changed) 10)
                  (println "  ... and" (- (count changed) 10) "more"))))))
        (println "No cache found. Run 'analyze' to create one."))
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
