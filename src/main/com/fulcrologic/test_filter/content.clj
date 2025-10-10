(ns com.fulcrologic.test-filter.content
  "Content extraction and hashing for detecting semantic changes in code."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types])
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
  (try
    (let [lines (read-file-lines file-path)]
      (when (and start-line end-line
              (<= start-line (dec (count lines)))
              (<= end-line (dec (count lines))))
        (str/join "\n"
          (subvec lines start-line (inc end-line)))))
    (catch Exception e
      nil)))

;; -----------------------------------------------------------------------------
;; Content Normalization
;; -----------------------------------------------------------------------------

(defn strip-docstring-from-form
  "Removes docstring from a def* form.

  Docstrings can appear in two positions:
  1. After the name, before args: (defn name \"doc\" [args] body)
  2. After args, before body: (defn name [args] \"doc\" body)

  The reader may parse them differently depending on formatting.

  Args:
    form - A Clojure form (list)

  Returns:
    The form without the docstring"
  [form]
  (if (and (seq? form)
        (symbol? (first form))
        (str/starts-with? (name (first form)) "def"))
    (let [[def-sym name-sym & rest-parts] form]
      (cond
        ;; Case 1: docstring before args (defn foo "doc" [x] ...)
        (and (seq rest-parts) (string? (first rest-parts)))
        (list* def-sym name-sym (rest rest-parts))

        ;; Case 2: docstring after args (defn foo [x] "doc" ...)
        ;; This happens when docstring is on a separate line in source
        (and (>= (count rest-parts) 2)
          (vector? (first rest-parts))
          (string? (second rest-parts)))
        (list* def-sym name-sym (first rest-parts) (drop 2 rest-parts))

        ;; No docstring, return as-is
        :else
        form))
    ;; Not a def form, return as-is
    form))

(defn remove-comments
  "Walks a form and removes comment forms."
  [form]
  (cond
    (seq? form)
    (let [result (keep remove-comments form)]
      (if (seq result) (apply list result) nil))

    (vector? form)
    (vec (keep remove-comments form))

    (map? form)
    (into {} (keep (fn [[k v]]
                     (let [new-k (remove-comments k)
                           new-v (remove-comments v)]
                       (when (and new-k new-v)
                         [new-k new-v])))
               form))

    (set? form)
    (set (keep remove-comments form))

    :else
    form))

(defn normalize-form-to-string
  "Converts a form back to a string with normalized formatting.

  Uses pr-str which gives consistent output regardless of original formatting."
  [form]
  (pr-str form))

(defn normalize-content
  "Normalizes source code content for semantic comparison.

  Uses the EDN reader to parse the code, removes docstrings and comments,
  then re-emits with pr-str for consistent formatting."
  [source-text]
  (when source-text
    (try
      (let [;; Parse the source text
            rdr  (reader-types/source-logging-push-back-reader source-text)
            form (reader/read {:read-cond :preserve :eof nil} rdr)]
        (when form
          (-> form
            strip-docstring-from-form
            remove-comments
            normalize-form-to-string)))
      (catch Exception e
        ;; If parsing fails, fall back to original text
        ;; This can happen with incomplete forms or syntax errors
        source-text))))

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
  (some-> source-text
    normalize-content
    sha256))

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
  (when (and line end-line)
    (when-let [source-text (extract-source-text file-path line end-line)]
      (when-let [content-hash (hash-content source-text)]
        {:symbol symbol
         :hash   content-hash
         :file   file-path}))))

(defn hash-file-symbols
  "Generates content hashes for all symbols defined in a file.

  Args:
    file-path - Path to the source file
    symbol-nodes - Map of {symbol -> node-data} from analyzer

  Returns:
    Map of {symbol -> hash-string}"
  [file-path symbol-nodes]
  (reduce (fn [hashes [sym node]]
            (if-let [hash-result (hash-symbol file-path node sym)]
              (assoc hashes sym (:hash hash-result))
              hashes))
    {}
    symbol-nodes))

(defn hash-graph-symbols
  "Generates content hashes for all symbols in a symbol graph.

  Args:
    symbol-graph - Symbol graph from analyzer with :nodes

  Returns:
    Map of {symbol -> hash-string}"
  [symbol-graph]
  (let [nodes           (:nodes symbol-graph)
        ;; Group symbols by file
        symbols-by-file (group-by (fn [[_sym node]] (:file node)) nodes)]

    (reduce (fn [all-hashes [file-path symbol-nodes]]
              (merge all-hashes
                (hash-file-symbols file-path (into {} symbol-nodes))))
      {}
      symbols-by-file)))

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
