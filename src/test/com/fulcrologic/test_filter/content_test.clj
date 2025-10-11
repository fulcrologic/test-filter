(ns com.fulcrologic.test-filter.content-test
  "Unit tests for content extraction and hashing."
  (:require [clojure.string :as str]
            [com.fulcrologic.test-filter.content :as content]
            [fulcro-spec.core :refer [=> assertions behavior specification]]))

;; -----------------------------------------------------------------------------
;; Test Data and Helpers
;; -----------------------------------------------------------------------------

(def sample-source
  "(defn calculate
  \"Calculates something.\"
  [x y]
  (+ x y))")

(def sample-source-no-docstring
  "(defn calculate
  [x y]
  (+ x y))")

(def sample-source-with-comments
  "(defn calculate
  [x y]
  ;; Add the numbers
  (+ x y) ;; inline comment
  )")

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(specification "normalize-content"
  (behavior "strips docstrings from def forms"
    (let [result (content/normalize-content sample-source)]

      (assertions
        "returns a string"
        (string? result) => true

        "does not contain docstring"
        (str/includes? result "Calculates") => false

        "contains function logic"
        (str/includes? result "defn") => true
        (str/includes? result "calculate") => true
        (str/includes? result "+ x y") => true)))

  (behavior "produces same output regardless of docstring position"
    (let [doc-after-name "(defn calculate \"Doc\" [x y] (+ x y))"
          doc-after-args "(defn calculate [x y] \"Doc\" (+ x y))"
          no-doc         "(defn calculate [x y] (+ x y))"
          hash1          (content/hash-content doc-after-name)
          hash2          (content/hash-content doc-after-args)
          hash3          (content/hash-content no-doc)]

      (assertions
        "all three produce same hash"
        hash1 => hash2
        hash2 => hash3)))

  (behavior "handles docstrings with escaped quotes"
    (let [with-escaped "(defn foo \"This is a \\\"docstring\\\" with quotes\" [x] (+ x 1))"
          without-doc  "(defn foo [x] (+ x 1))"
          result       (content/normalize-content with-escaped)]

      (assertions
        "removes docstring with escaped quotes"
        (str/includes? result "docstring") => false
        (str/includes? result "(defn foo [x] (+ x 1))") => true

        "produces same hash as version without docstring"
        (content/hash-content with-escaped) => (content/hash-content without-doc))))

  (behavior "handles multiline docstrings"
    (let [multiline   "(defn bar\n  \"This is a docstring\n  that spans multiple\n  lines with details\"\n  [x y]\n  (* x y))"
          without-doc "(defn bar [x y] (* x y))"
          result      (content/normalize-content multiline)]

      (assertions
        "removes multiline docstring"
        (str/includes? result "docstring") => false
        (str/includes? result "spans") => false
        (str/includes? result "(defn bar [x y] (* x y))") => true

        "produces same hash as version without docstring"
        (content/hash-content multiline) => (content/hash-content without-doc))))

  (behavior "handles multiline docstrings with escaped quotes"
    (let [complex     "(defn baz\n  \"Multi-line with \\\"escaped\\\" quotes\n  on multiple lines\"\n  [a b c]\n  (+ a b c))"
          without-doc "(defn baz [a b c] (+ a b c))"
          result      (content/normalize-content complex)]

      (assertions
        "removes complex docstring"
        (str/includes? result "Multi-line") => false
        (str/includes? result "escaped") => false
        (str/includes? result "(defn baz [a b c] (+ a b c))") => true

        "produces same hash as version without docstring"
        (content/hash-content complex) => (content/hash-content without-doc))))

  (behavior "preserves string literals that are not docstrings"
    (let [with-string "(defn greet [name] (str \"Hello, \" name))"
          result      (content/normalize-content with-string)]

      (assertions
        "keeps string literals in function body"
        (str/includes? result "\"Hello, \"") => true
        (str/includes? result "name") => true)))

  (behavior "preserves source text exactly (no reader expansion)"
    (let [code-with-syntax-quote "(p `get-entity-min-issue-date (foo))"
          normalized             (content/normalize-content code-with-syntax-quote)]

      (assertions
        "does not expand syntax-quote"
        (str/includes? normalized "`get-entity-min-issue-date") => true
        (str/includes? normalized "user/get-entity-min-issue-date") => false)))

  (behavior "produces deterministic hashes for syntax-quoted symbols"
    (let [code  "(p `get-entity-min-issue-date (foo))"
          hash1 (content/hash-content code)
          hash2 (content/hash-content code)]

      (assertions
        "hash is stable across multiple calls"
        hash1 => hash2)))

  (behavior "produces deterministic hashes for anonymous functions"
    (let [code  "(def underscore #(str/replace % #\"-\" \"_\"))"
          hash1 (content/hash-content code)
          hash2 (content/hash-content code)]

      (assertions
        "hash is stable across multiple calls"
        hash1 => hash2)))

  (behavior "handles nil input"
    (let [result (content/normalize-content nil)]

      (assertions
        "returns nil"
        result => nil)))

  (behavior "falls back to original on processing errors"
    (let [invalid "(defn broken [x"
          result  (content/normalize-content invalid)]

      (assertions
        "returns original text when processing fails"
        result => invalid))))

(specification "sha256"
  (behavior "generates SHA256 hash of string"
    (let [result (content/sha256 "hello world")]

      (assertions
        "returns a hex string"
        (string? result) => true

        "is 64 characters (256 bits in hex)"
        (count result) => 64

        "contains only hex characters"
        (re-matches #"[0-9a-f]+" result) => result)))

  (behavior "produces consistent hashes"
    (let [hash1 (content/sha256 "test")
          hash2 (content/sha256 "test")]

      (assertions
        "same input produces same hash"
        hash1 => hash2)))

  (behavior "produces different hashes for different inputs"
    (let [hash1 (content/sha256 "test1")
          hash2 (content/sha256 "test2")]

      (assertions
        "different inputs produce different hashes"
        (not= hash1 hash2) => true)))

  (behavior "handles nil input"
    (let [result (content/sha256 nil)]

      (assertions
        "returns nil"
        result => nil))))

(specification "hash-content"
  (behavior "combines normalization and hashing"
    (let [with-doc    "(defn calculate \"Doc\" [x y] (+ x y))"
          without-doc "(defn calculate [x y] (+ x y))"
          hash1       (content/hash-content with-doc)
          hash2       (content/hash-content without-doc)]

      (assertions
        "returns hex string"
        (string? hash1) => true

        "docstring removal produces same hash when formatting is identical"
        hash1 => hash2)))

  (behavior "ignores whitespace differences (Clojure is whitespace-agnostic)"
    (let [compact   "(defn foo [x] (* x 2))"
          spaced    "(defn foo [x]  (*  x  2))"
          multiline "(defn foo\n  [x]\n  (* x 2))"
          tabs      "(defn\tfoo\t[x]\t(*\tx\t2))"
          hash1     (content/hash-content compact)
          hash2     (content/hash-content spaced)
          hash3     (content/hash-content multiline)
          hash4     (content/hash-content tabs)]

      (assertions
        "all whitespace variations produce same hash"
        hash1 => hash2
        hash2 => hash3
        hash3 => hash4)))

  (behavior "detects actual logic changes"
    (let [original "(defn foo [x] (* x 2))"
          changed  "(defn foo [x] (* x 3))"
          hash1    (content/hash-content original)
          hash2    (content/hash-content changed)]

      (assertions
        "logic changes produce different hashes"
        (not= hash1 hash2) => true)))

  (behavior "handles nil input"
    (let [result (content/hash-content nil)]

      (assertions
        "returns nil"
        result => nil))))

(specification "read-file-lines and extract-source-text"
  (let [temp-file (java.io.File/createTempFile "test-filter" ".clj")
        temp-path (.getAbsolutePath temp-file)]

    ;; Setup: Write test content
    (spit temp-path "(ns test)\n\n(defn foo [x]\n  (* x 2))\n\n(defn bar [y]\n  (+ y 3))")

    (behavior "read-file-lines returns vector with 1-indexed lines"
      (let [lines (content/read-file-lines temp-path)]

        (assertions
          "first element is nil (for 1-indexing)"
          (first lines) => nil

          "line 1 is ns declaration"
          (str/includes? (get lines 1) "(ns test)") => true)))

    (behavior "extract-source-text extracts line range"
      (let [source (content/extract-source-text temp-path 3 4)]

        (assertions
          "extracts the specified lines"
          (str/includes? source "defn foo") => true
          (str/includes? source "(* x 2)") => true

          "does not include other lines"
          (str/includes? source "defn bar") => false)))

    (behavior "handles invalid line ranges gracefully"
      (let [source (content/extract-source-text temp-path 100 200)]

        (assertions
          "returns nil for out-of-range"
          source => nil)))

    ;; Cleanup
    (.delete temp-file)))

(specification "hash-symbol"
  (let [temp-file (java.io.File/createTempFile "test-filter" ".clj")
        temp-path (.getAbsolutePath temp-file)]

    ;; Setup
    (spit temp-path "(ns test)\n\n(defn calculate\n  \"Doc\"\n  [x y]\n  (+ x y))")

    (behavior "generates hash for symbol definition"
      (let [symbol-node {:line 3 :end-line 6}
            result      (content/hash-symbol temp-path symbol-node 'test/calculate)]

        (assertions
          "returns map with symbol and hash"
          (:symbol result) => 'test/calculate
          (string? (:hash result)) => true
          (:file result) => temp-path)))

    (behavior "returns nil for invalid node"
      (let [symbol-node {:line nil :end-line nil}
            result      (content/hash-symbol temp-path symbol-node 'test/invalid)]

        (assertions
          "returns nil when line info missing"
          result => nil)))

    ;; Cleanup
    (.delete temp-file)))

(specification "hash-file-symbols"
  (let [temp-file (java.io.File/createTempFile "test-filter" ".clj")
        temp-path (.getAbsolutePath temp-file)]

    ;; Setup
    (spit temp-path "(ns test)\n\n(defn foo [x] x)\n\n(defn bar [y] y)")

    (behavior "hashes all symbols in a file"
      (let [symbol-nodes {'test/foo {:line 3 :end-line 3}
                          'test/bar {:line 5 :end-line 5}}
            result       (content/hash-file-symbols temp-path symbol-nodes)]

        (assertions
          "returns map of symbol to hash"
          (map? result) => true

          "includes all symbols"
          (contains? result 'test/foo) => true
          (contains? result 'test/bar) => true

          "hashes are different"
          (not= (get result 'test/foo) (get result 'test/bar)) => true)))

    ;; Cleanup
    (.delete temp-file)))

(specification "hash-graph-symbols"
  (let [temp-file1 (java.io.File/createTempFile "test-filter-1" ".clj")
        temp-file2 (java.io.File/createTempFile "test-filter-2" ".clj")
        temp-path1 (.getAbsolutePath temp-file1)
        temp-path2 (.getAbsolutePath temp-file2)]

    ;; Setup
    (spit temp-path1 "(ns app.core)\n\n(defn handler [x] x)")
    (spit temp-path2 "(ns app.db)\n\n(defn query [q] q)")

    (behavior "hashes all symbols in symbol graph"
      (let [symbol-graph {:nodes {'app.core/handler {:symbol   'app.core/handler
                                                     :file     temp-path1
                                                     :line     3
                                                     :end-line 3}
                                  'app.db/query     {:symbol   'app.db/query
                                                     :file     temp-path2
                                                     :line     3
                                                     :end-line 3}}}
            result       (content/hash-graph-symbols symbol-graph)]

        (assertions
          "returns map of all symbols"
          (contains? result 'app.core/handler) => true
          (contains? result 'app.db/query) => true

          "generates valid hashes"
          (string? (get result 'app.core/handler)) => true
          (= 64 (count (get result 'app.core/handler))) => true)))

    ;; Cleanup
    (.delete temp-file1)
    (.delete temp-file2)))

(specification "find-changed-symbols"
  (behavior "finds symbols with different hashes"
    (let [old-hashes {'foo/bar "hash1" 'foo/baz "hash2"}
          new-hashes {'foo/bar "hash1" 'foo/baz "hash3" 'foo/qux "hash4"}
          changed    (content/find-changed-symbols old-hashes new-hashes)]

      (assertions
        "returns set of changed symbols"
        (set? changed) => true

        "includes modified symbol"
        (contains? changed 'foo/baz) => true

        "includes new symbol"
        (contains? changed 'foo/qux) => true

        "excludes unchanged symbol"
        (contains? changed 'foo/bar) => false)))

  (behavior "handles empty maps"
    (let [changed (content/find-changed-symbols {} {})]

      (assertions
        "returns empty set"
        changed => #{}))))

(specification "find-deleted-symbols"
  (behavior "finds symbols present in old but not in new"
    (let [old-hashes {'foo/bar "hash1" 'foo/baz "hash2" 'foo/deleted "hash3"}
          new-hashes {'foo/bar "hash1" 'foo/baz "hash2"}
          deleted    (content/find-deleted-symbols old-hashes new-hashes)]

      (assertions
        "returns set of deleted symbols"
        (set? deleted) => true

        "includes deleted symbol"
        (contains? deleted 'foo/deleted) => true

        "excludes existing symbols"
        (contains? deleted 'foo/bar) => false
        (contains? deleted 'foo/baz) => false)))

  (behavior "handles empty maps"
    (let [deleted (content/find-deleted-symbols {} {})]

      (assertions
        "returns empty set"
        deleted => #{}))))
