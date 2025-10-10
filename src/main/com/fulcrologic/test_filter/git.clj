(ns com.fulcrologic.test-filter.git
  "Git operations for detecting code changes between revisions."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

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
                :stderr    (:err result)})))))

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
                :stderr    (:err result)
                :rev-spec  rev-spec})))))

(defn has-uncommitted-changes?
  "Returns true if there are uncommitted changes in the working directory."
  []
  (let [result (shell/sh "git" "status" "--porcelain")]
    (if (zero? (:exit result))
      (not (str/blank? (:out result)))
      (throw (ex-info "Failed to check git status"
               {:exit-code (:exit result)
                :stderr    (:err result)})))))

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
   (let [args   (if (nil? to-rev)
                  ;; nil means compare to working directory
                  ["git" "diff" from-rev]
                  ;; otherwise compare between two revisions
                  ["git" "diff" from-rev to-rev])
         result (apply shell/sh args)]
     (if (zero? (:exit result))
       (:out result)
       (throw (ex-info "Failed to get git diff"
                {:exit-code (:exit result)
                 :stderr    (:err result)
                 :from      from-rev
                 :to        (or to-rev "working directory")}))))))

(defn changed-files
  "Returns a set of files that changed between two revisions.

  Args:
    from-rev - Starting revision
    to-rev - Ending revision (nil = working directory)

  Returns:
    Set of file paths"
  ([from-rev]
   (changed-files from-rev nil))
  ([from-rev to-rev]
   (let [args   (if (nil? to-rev)
                  ;; nil means compare to working directory
                  ["git" "diff" "--name-only" from-rev]
                  ;; otherwise compare between two revisions
                  ["git" "diff" "--name-only" from-rev to-rev])
         result (apply shell/sh args)]
     (if (zero? (:exit result))
       (set (remove str/blank? (str/split-lines (:out result))))
       (throw (ex-info "Failed to get changed files"
                {:exit-code (:exit result)
                 :stderr    (:err result)
                 :from      from-rev
                 :to        (or to-rev "working directory")}))))))

(defn uncommitted-files
  "Returns a set of files that have uncommitted changes in the working directory.

  This includes:
  - Modified files (staged and unstaged)
  - New files (staged and unstaged)

  Does NOT include:
  - Deleted files (since we can't re-hash them)
  - Untracked files (not in git yet)

  Returns:
    Set of file paths relative to repo root"
  []
  (let [result (shell/sh "git" "diff" "--name-only" "HEAD")]
    (if (zero? (:exit result))
      (set (remove str/blank? (str/split-lines (:out result))))
      (throw (ex-info "Failed to get uncommitted files"
               {:exit-code (:exit result)
                :stderr    (:err result)})))))

;; -----------------------------------------------------------------------------
;; Diff Parsing
;; -----------------------------------------------------------------------------

;; Removed: parse-diff-line-ranges, symbols-in-range, find-changed-symbols
;; These are no longer needed with content-hash based change detection.
;; Change detection is now handled by comparing content hashes in content.clj

;; -----------------------------------------------------------------------------
;; Symbol Change Detection
;; -----------------------------------------------------------------------------

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
(comment "test change")
