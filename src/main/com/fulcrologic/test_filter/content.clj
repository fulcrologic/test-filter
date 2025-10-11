(ns com.fulcrologic.test-filter.content
  "Content extraction and hashing for detecting semantic changes in code."
  (:require
    [clojure.string :as str]
    [taoensso.tufte :refer [p]])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

;; -----------------------------------------------------------------------------
;; Source Text Extraction
;; -----------------------------------------------------------------------------

(defn read-file-lines
  "Reads a file and returns a vector of lines (1-indexed for convenience)."
  [file-path]
  (vec (cons nil (str/split-lines (slurp file-path)))))

(defn extract-source-text
  "Extracts the source text for a symbol from a file using line/column ranges.

  Args:
    file-path - Path to the source file
    start-line - Starting line number (1-indexed)
    start-col - Starting column number (1-indexed)
    end-line - Ending line number (1-indexed)
    end-col - Ending column number (1-indexed)

  Returns:
    String containing the source text, or nil if extraction fails"
  [file-path start-line end-line]
  (p `extract-source-text
    (try
      (let [lines (read-file-lines file-path)]
        (when (and start-line end-line
                (<= start-line (dec (count lines)))
                (<= end-line (dec (count lines))))
          (str/join "\n"
            (subvec lines start-line (inc end-line)))))
      (catch Exception e
        nil))))

(defn extract-source-text-from-lines
  "Extracts source text from a pre-loaded lines vector.

  This is more efficient than extract-source-text when you need to extract
  multiple symbols from the same file.

  Args:
    lines - Vector of lines (1-indexed, with nil at position 0)
    start-line - Starting line number (1-indexed)
    end-line - Ending line number (1-indexed)

  Returns:
    String containing the source text, or nil if extraction fails"
  [lines start-line end-line]
  (try
    (when (and start-line end-line
            (<= start-line (dec (count lines)))
            (<= end-line (dec (count lines))))
      (str/join "\n"
        (subvec lines start-line (inc end-line))))
    (catch Exception e
      nil)))

;; -----------------------------------------------------------------------------
;; Content Normalization - String-based Parser
;; -----------------------------------------------------------------------------

(defn- find-string-end
  "Finds the end position of a string starting at idx (after opening quote).
  Returns the index after the closing quote, or nil if not found.
  Handles escaped quotes correctly."
  [s idx]
  (loop [i idx]
    (when (< i (count s))
      (let [ch (get s i)]
        (cond
          (= ch \\) (recur (+ i 2))                         ; Skip escaped character
          (= ch \") (inc i)                                 ; Found closing quote
          :else (recur (inc i)))))))

(defn- skip-whitespace
  "Returns index of first non-whitespace character starting from idx."
  [s idx]
  (loop [i idx]
    (if (and (< i (count s))
          (Character/isWhitespace (char (get s i))))
      (recur (inc i))
      i)))

(defn- find-matching-bracket
  "Finds the closing bracket matching the opening bracket at idx.
  Returns the index after the closing bracket, or nil if not found.
  Handles strings and nested brackets correctly."
  [s idx]
  (let [open-ch  (get s idx)
        close-ch (case open-ch
                   \[ \]
                   \( \)
                   \{ \}
                   nil)]
    (when close-ch
      (loop [i     (inc idx)
             depth 1]
        (when (< i (count s))
          (let [ch (get s i)]
            (cond
              ;; Handle strings - skip to end
              (= ch \")
              (if-let [end (find-string-end s (inc i))]
                (recur end depth)
                nil)

              ;; Handle nested brackets
              (= ch open-ch)
              (recur (inc i) (inc depth))

              (= ch close-ch)
              (if (= depth 1)
                (inc i)                                     ; Found matching close
                (recur (inc i) (dec depth)))

              :else
              (recur (inc i) depth))))))))

(defn- remove-docstring-from-def
  "Removes docstring from a def* form in source text.
  Returns the modified source text, or original if no docstring found.

  Handles:
  - (defn name \"doc\" [args] ...) - after name
  - (defn name [args] \"doc\" ...) - after args"
  [s]
  (let [len (count s)]
    (loop [i      0
           result (StringBuilder.)]
      (if (>= i len)
        (str result)
        (let [ch (get s i)]
          (cond
            ;; Handle strings - copy them through
            (= ch \")
            (if-let [end (find-string-end s (inc i))]
              (do
                (.append result (subs s i end))
                (recur end result))
              (recur (inc i) result))

            ;; Look for (def* pattern
            (and (= ch \()
              (< (inc i) len)
              (= \d (get s (inc i))))
            (let [def-start i
                  ;; Find end of def* symbol
                  def-end   (loop [j (inc i)]
                              (if (and (< j len)
                                    (let [c (get s j)]
                                      (or (Character/isLetterOrDigit (char c))
                                        (= c \-))))
                                (recur (inc j))
                                j))]
              ;; Check if it's actually a def* form
              (if (and (str/starts-with? (subs s (inc i) def-end) "def")
                    (< def-end len)
                    (Character/isWhitespace (get s def-end)))
                ;; It's a def form - look for docstring
                (let [after-def  (skip-whitespace s def-end)
                      ;; Find end of name symbol
                      name-end   (loop [j after-def]
                                   (if (and (< j len)
                                         (let [c (get s j)]
                                           (not (Character/isWhitespace (char c)))))
                                     (recur (inc j))
                                     j))
                      after-name (skip-whitespace s name-end)]
                  ;; Check if there's a docstring after name
                  (if (and (< after-name len)
                        (= \" (get s after-name)))
                    ;; Found docstring after name
                    (if-let [doc-end (find-string-end s (inc after-name))]
                      (do
                        ;; Copy everything up to docstring
                        (.append result (subs s i after-name))
                        ;; Skip docstring, continue after it
                        (recur doc-end result))
                      (do
                        (.append result ch)
                        (recur (inc i) result)))
                    ;; No docstring after name, check after args
                    (if (and (< after-name len)
                          (= \[ (get s after-name)))
                      (if-let [args-end (find-matching-bracket s after-name)]
                        (let [after-args (skip-whitespace s args-end)]
                          (if (and (< after-args len)
                                (= \" (get s after-args)))
                            ;; Found docstring after args
                            (if-let [doc-end (find-string-end s (inc after-args))]
                              (do
                                ;; Copy everything up to docstring
                                (.append result (subs s i after-args))
                                ;; Skip docstring, continue after it
                                (recur doc-end result))
                              (do
                                (.append result ch)
                                (recur (inc i) result)))
                            ;; No docstring after args
                            (do
                              (.append result ch)
                              (recur (inc i) result))))
                        (do
                          (.append result ch)
                          (recur (inc i) result)))
                      ;; No args vector
                      (do
                        (.append result ch)
                        (recur (inc i) result)))))
                ;; Not a def form
                (do
                  (.append result ch)
                  (recur (inc i) result))))

            ;; Regular character
            :else
            (do
              (.append result ch)
              (recur (inc i) result))))))))

(defn normalize-content
  "Normalizes source code content for semantic comparison.

  This function:
  1. Removes docstrings from def* forms using a proper string parser
  2. Normalizes whitespace (since Clojure is whitespace-agnostic)

  Removes docstrings from these positions:
  - (defn name \"doc\" [args] ...) - after name
  - (defn name [args] \"doc\" ...) - after args
  - (def name \"doc\" value) - after name

  Note: Uses string-based parsing to preserve exact source,
  avoiding non-deterministic reader expansions."
  [source-text]
  (p `normalize-content
    (when source-text
      (try
        (let [;; Remove docstrings using proper parser
              without-docs (remove-docstring-from-def source-text)
              ;; Normalize all whitespace to single spaces
              ;; This makes hashing whitespace-agnostic (as Clojure code is just data)
              normalized   (-> without-docs
                             ;; Replace all whitespace sequences with single space
                             (str/replace #"\s+" " ")
                             ;; Trim leading/trailing whitespace
                             str/trim)]
          normalized)
        (catch Exception e
          ;; If processing fails, return original
          source-text)))))

;; -----------------------------------------------------------------------------
;; Hashing
;; -----------------------------------------------------------------------------

(defn sha256
  "Generates a SHA256 hash of the input string."
  [^String s]
  (when s
    (let [digest     (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes s StandardCharsets/UTF_8))]
      ;; Convert to hex string
      (apply str (map #(format "%02x" %) hash-bytes)))))

(defn hash-content
  "Generates a content hash for normalized source text.

  Returns:
    SHA256 hex string, or nil if source-text is nil"
  [source-text]
  (p `hash-content
    (some-> source-text
      normalize-content
      sha256)))

;; -----------------------------------------------------------------------------
;; Symbol Content Hashing
;; -----------------------------------------------------------------------------

(defn hash-symbol
  "Generates a content hash for a symbol's definition.

  Args:
    file-path - Path to the source file
    symbol-node - Symbol node from analyzer with :line and :end-line

  Returns:
    {:symbol symbol
     :hash SHA256-hex-string
     :file file-path}

    or nil if extraction/hashing fails"
  [file-path {:keys [line end-line] :as symbol-node} symbol]
  (p `hash-symbol
    (when (and line end-line)
      (when-let [source-text (extract-source-text file-path line end-line)]
        (when-let [content-hash (hash-content source-text)]
          {:symbol symbol
           :hash   content-hash
           :file   file-path})))))

(defn hash-file-symbols
  "Generates content hashes for all symbols defined in a file.

  OPTIMIZED: Reads the file only once and extracts all symbol hashes.

  Args:
    file-path - Path to the source file
    symbol-nodes - Map of {symbol -> node-data} from analyzer

  Returns:
    Map of {symbol -> hash-string}"
  [file-path symbol-nodes]
  (p `hash-file-symbols
    (try
      ;; Read file once for all symbols
      (let [lines (read-file-lines file-path)]
        (reduce (fn [hashes [sym node]]
                  (let [{:keys [line end-line]} node]
                    (if-let [source-text (extract-source-text-from-lines lines line end-line)]
                      (if-let [content-hash (hash-content source-text)]
                        (assoc hashes sym content-hash)
                        hashes)
                      hashes)))
          {}
          symbol-nodes))
      (catch Exception e
        ;; If file read fails, return empty map
        {}))))

(defn hash-graph-symbols
  "Generates content hashes for all symbols in a symbol graph.

  Args:
    symbol-graph - Symbol graph from analyzer with :nodes

  Returns:
    Map of {symbol -> hash-string}"
  [symbol-graph]
  (p `hash-graph-symbols
    (let [nodes           (:nodes symbol-graph)
          ;; Group symbols by file
          symbols-by-file (group-by (fn [[_sym node]] (:file node)) nodes)]

      (reduce (fn [all-hashes [file-path symbol-nodes]]
                (merge all-hashes
                  (hash-file-symbols file-path (into {} symbol-nodes))))
        {}
        symbols-by-file))))

(defn rehash-files
  "Re-computes content hashes for symbols in specific files from a graph.

  This is useful for updating hashes when files have changed without doing
  a full analysis. Takes the existing graph structure and re-reads the files
  to compute fresh hashes.

  Args:
    symbol-graph - Symbol graph with :nodes
    file-paths - Collection of file paths to re-hash

  Returns:
    Map of {symbol -> hash-string} for symbols in the specified files"
  [symbol-graph file-paths]
  (p `rehash-files
    (let [nodes            (:nodes symbol-graph)
          file-set         (set file-paths)
          ;; Filter to only symbols in the specified files
          symbols-in-files (filter (fn [[_sym node]]
                                     (contains? file-set (:file node)))
                             nodes)
          ;; Group by file
          by-file          (group-by (fn [[_sym node]] (:file node))
                             symbols-in-files)]

      (reduce (fn [hashes [file-path symbol-nodes]]
                (merge hashes
                  (hash-file-symbols file-path (into {} symbol-nodes))))
        {}
        by-file))))

;; -----------------------------------------------------------------------------
;; Hash Comparison
;; -----------------------------------------------------------------------------

(defn find-changed-symbols
  "Compares two hash maps to find symbols with changed content.

  Args:
    old-hashes - Map of {symbol -> hash} from cache
    new-hashes - Map of {symbol -> hash} from fresh analysis

  Returns:
    Set of symbols that have changed (different hash or new symbols)"
  [old-hashes new-hashes]
  (set (for [[sym new-hash] new-hashes
             :let [old-hash (get old-hashes sym)]
             :when (not= old-hash new-hash)]
         sym)))

(defn find-deleted-symbols
  "Finds symbols that existed in old hashes but not in new hashes.

  Args:
    old-hashes - Map of {symbol -> hash} from cache
    new-hashes - Map of {symbol -> hash} from fresh analysis

  Returns:
    Set of symbols that were deleted"
  [old-hashes new-hashes]
  (set (filter #(not (contains? new-hashes %))
         (keys old-hashes))))

(comment
  ;; Example usage:

  ;; Extract and hash a single symbol
  (def source "(defn example [x]\n  \"A docstring\"\n  (* x 2))")
  (normalize-content source)
  ;; => "(defn example [x] (* x 2))"

  (hash-content source)
  ;; => "abc123..."

  ;; Compare hashes
  (def old {:foo/bar "hash1" :foo/baz "hash2"})
  (def new {:foo/bar "hash1" :foo/baz "hash3" :foo/qux "hash4"})

  (find-changed-symbols old new)
  ;; => #{:foo/baz :foo/qux}

  (find-deleted-symbols old new)
  ;; => #{}
  )
