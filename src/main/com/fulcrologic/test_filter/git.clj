(ns com.fulcrologic.test-filter.git
  "Git operations for detecting code changes between revisions."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.set :as set]))

;; -----------------------------------------------------------------------------
;; Git Commands
;; -----------------------------------------------------------------------------

(defn current-revision
  "Returns the current git revision (commit SHA).

  This function retrieves the full 40-character SHA hash of the current HEAD commit."
  []
  (let [result (shell/sh "git" "rev-parse" "HEAD")]
    (if (zero? (:exit result))
      (str/trim (:out result))
      (throw (ex-info "Failed to get current git revision"
                      {:exit-code (:exit result)
                       :stderr (:err result)})))))

(defn resolve-revision
  "Resolves a git revision reference (partial SHA, branch name, tag, etc.) to a full SHA.

  Examples:
    (resolve-revision \"abc123\")     ; partial SHA
    (resolve-revision \"HEAD~3\")     ; relative reference
    (resolve-revision \"main\")       ; branch name
    (resolve-revision \"v1.0.0\")     ; tag

  Returns the full commit SHA."
  [rev-spec]
  (let [result (shell/sh "git" "rev-parse" rev-spec)]
    (if (zero? (:exit result))
      (str/trim (:out result))
      (throw (ex-info "Failed to resolve git revision"
                      {:exit-code (:exit result)
                       :stderr (:err result)
                       :rev-spec rev-spec})))))

(defn has-uncommitted-changes?
  "Returns true if there are uncommitted changes in the working directory."
  []
  (let [result (shell/sh "git" "status" "--porcelain")]
    (if (zero? (:exit result))
      (not (str/blank? (:out result)))
      (throw (ex-info "Failed to check git status"
                      {:exit-code (:exit result)
                       :stderr (:err result)})))))

(defn git-diff
  "Gets the diff between two revisions.

  Args:
    from-rev - Starting revision (commit SHA)
    to-rev - Ending revision (nil = working directory, defaults to HEAD)

  Returns:
    String containing the git diff output"
  ([from-rev]
   (git-diff from-rev "HEAD"))
  ([from-rev to-rev]
   (let [args (if (nil? to-rev)
                ;; nil means compare to working directory
                ["git" "diff" from-rev]
                ;; otherwise compare between two revisions
                ["git" "diff" from-rev to-rev])
         result (apply shell/sh args)]
     (if (zero? (:exit result))
       (:out result)
       (throw (ex-info "Failed to get git diff"
                       {:exit-code (:exit result)
                        :stderr (:err result)
                        :from from-rev
                        :to (or to-rev "working directory")}))))))

(defn changed-files
  "Returns a list of files that changed between two revisions.

  Args:
    from-rev - Starting revision
    to-rev - Ending revision (defaults to HEAD)

  Returns:
    Vector of file paths"
  ([from-rev]
   (changed-files from-rev "HEAD"))
  ([from-rev to-rev]
   (let [result (shell/sh "git" "diff" "--name-only" from-rev to-rev)]
     (if (zero? (:exit result))
       (vec (remove str/blank? (str/split-lines (:out result))))
       (throw (ex-info "Failed to get changed files"
                       {:exit-code (:exit result)
                        :stderr (:err result)
                        :from from-rev
                        :to to-rev}))))))

;; -----------------------------------------------------------------------------
;; Diff Parsing
;; -----------------------------------------------------------------------------

(defn parse-diff-line-ranges
  "Parses git diff output to extract changed line ranges per file.

  Returns:
    Map of {file-path -> [{:start-line N :end-line M :type :added/:deleted/:modified}]}"
  [diff-output]
  (let [lines (str/split-lines diff-output)
        ;; Parse unified diff format
        file-pattern #"^\+\+\+ b/(.+)$"
        hunk-pattern #"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$"]
    (loop [lines lines
           current-file nil
           result {}]
      (if (empty? lines)
        result
        (let [line (first lines)]
          (cond
            ;; New file header
            (re-matches file-pattern line)
            (let [[_ file] (re-matches file-pattern line)]
              (recur (rest lines) file result))

            ;; Hunk header (@@ -old +new @@)
            (and current-file (re-matches hunk-pattern line))
            (let [[_ _old-start _old-count new-start new-count]
                  (re-matches hunk-pattern line)
                  start (parse-long new-start)
                  count (if new-count (parse-long new-count) 1)
                  end (+ start count -1)]
              (recur (rest lines)
                     current-file
                     (update result current-file
                             (fnil conj [])
                             {:start-line start
                              :end-line end
                              :type :modified})))

            :else
            (recur (rest lines) current-file result)))))))

;; -----------------------------------------------------------------------------
;; Symbol Change Detection
;; -----------------------------------------------------------------------------

(defn symbols-in-range
  "Returns symbols whose definitions overlap with the given line range.

  Args:
    symbol-graph - Symbol graph from analyzer
    file - File path
    start-line - Start of changed range
    end-line - End of changed range

  Returns:
    Set of symbols that overlap with the range"
  [symbol-graph file start-line end-line]
  (let [nodes (:nodes symbol-graph)]
    (set (for [[sym node] nodes
               :when (= (:file node) file)
               :when (and (:line node) (:end-line node))
               ;; Check if ranges overlap
               :when (not (or (< end-line (:line node))
                              (> start-line (:end-line node))))]
           sym))))

(defn find-changed-symbols
  "Finds all symbols that have changed between two revisions.

  Args:
    symbol-graph - Symbol graph from analyzer
    from-rev - Starting revision
    to-rev - Ending revision (nil = working directory, defaults to HEAD)

  Returns:
    Set of symbols that have changed"
  ([symbol-graph from-rev]
   (find-changed-symbols symbol-graph from-rev "HEAD"))
  ([symbol-graph from-rev to-rev]
   (let [diff (git-diff from-rev to-rev)
         changed-ranges (parse-diff-line-ranges diff)]
     (reduce (fn [changed-syms [file ranges]]
               (reduce (fn [syms {:keys [start-line end-line]}]
                         (set/union
                          syms
                          (symbols-in-range symbol-graph file start-line end-line)))
                       changed-syms
                       ranges))
             #{}
             changed-ranges))))

(comment
  ;; Example usage:
  (current-revision)
  ;; => "abc123def456..."

  (changed-files "HEAD~1")
  ;; => ["src/main/test_filter/analyzer.clj" "src/main/test_filter/git.clj"]

  (require '[com.fulcrologic.test-filter.analyzer :as analyzer])
  (def graph (analyzer/build-symbol-graph (analyzer/run-analysis {:paths ["src"]})))

  (find-changed-symbols graph "HEAD~1")
  ;; => #{test-filter.git/current-revision test-filter.analyzer/run-analysis}
  )
