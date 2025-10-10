(ns com.fulcrologic.test-filter.content-test
  "Unit tests for content extraction and hashing."
  (:require [clojure.string :as str]
            [com.fulcrologic.test-filter.content :as content]
            [fulcro-spec.core :refer [=> assertions behavior component specification]]))

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

(specification "strip-docstring-from-form"
  (behavior "removes docstring after function name"
    (let [form   '(defn example "A docstring" [x] (* x 2))
          result (content/strip-docstring-from-form form)]

      (assertions
        "removes the docstring"
        result => '(defn example [x] (* x 2)))))

  (behavior "removes docstring after args"
    (let [form   '(defn example [x] "A docstring" (* x 2))
          result (content/strip-docstring-from-form form)]

      (assertions
        "removes the docstring"
        result => '(defn example [x] (* x 2)))))

  (behavior "handles forms without docstrings"
    (let [form   '(defn example [x] (* x 2))
          result (content/strip-docstring-from-form form)]

      (assertions
        "returns form unchanged"
        result => form)))

  (behavior "only processes def* forms"
    (let [form   '(let [x "string"] x)
          result (content/strip-docstring-from-form form)]

      (assertions
        "returns non-def forms unchanged"
        result => form)))

  (behavior "handles various def forms"
    (component "def"
      (let [form   '(def ^:private config "Config value" {:a 1})
            result (content/strip-docstring-from-form form)]

        (assertions
          "removes docstring from def"
          result => '(def ^:private config {:a 1}))))

    (component "defmethod has different docstring position"
      (let [form   '(defmethod area :circle "Calculate circle area" [shape] 3.14)
            result (content/strip-docstring-from-form form)]

        (assertions
          "defmethod docstring is after dispatch value, not name"
          result => form)))))

(specification "remove-comments"
  (behavior "preserves quoted forms as-is"
    (let [form   '(defn foo [x] (comment "debug") (* x 2))
          result (content/remove-comments form)]

      (assertions
        "returns form unchanged"
        result => form)))

  (behavior "walks nested structures preserving them"
    (component "vectors"
      (let [form   '[1 2 3]
            result (content/remove-comments form)]

        (assertions
          "returns vectors unchanged"
          result => form)))

    (component "maps"
      (let [form   '{:a 1 :b 2 :c 3}
            result (content/remove-comments form)]

        (assertions
          "returns maps unchanged"
          result => form)))

    (component "sets"
      (let [form   '#{1 2 3}
            result (content/remove-comments form)]

        (assertions
          "returns sets unchanged"
          result => form))))

  (behavior "preserves non-comment forms"
    (let [form   '(defn example [x] (+ x 2))
          result (content/remove-comments form)]

      (assertions
        "returns unchanged when no comments"
        result => form))))

(specification "normalize-form-to-string"
  (behavior "converts form to consistent string representation"
    (let [form   '(defn foo [x] (* x 2))
          result (content/normalize-form-to-string form)]

      (assertions
        "returns a string"
        (string? result) => true

        "uses pr-str format"
        (str/includes? result "defn") => true))))

(specification "normalize-content"
  (behavior "strips docstrings and normalizes formatting"
    (let [result (content/normalize-content sample-source)]

      (assertions
        "returns a string"
        (string? result) => true

        "does not contain docstring"
        (str/includes? result "Calculates") => false

        "contains function logic"
        (str/includes? result "defn") => true
        (str/includes? result "calculate") => true)))

  (behavior "produces same output regardless of input formatting"
    (let [formatted "(defn calculate\n  \"Doc\"\n  [x y]\n  (+ x y))"
          compact   "(defn calculate \"Doc\" [x y] (+ x y))"
          result1   (content/normalize-content formatted)
          result2   (content/normalize-content compact)]

      (assertions
        "normalized versions are identical"
        result1 => result2)))

  (behavior "handles nil input"
    (let [result (content/normalize-content nil)]

      (assertions
        "returns nil"
        result => nil)))

  (behavior "falls back to original on parse errors"
    (let [invalid "(defn broken [x"
          result  (content/normalize-content invalid)]

      (assertions
        "returns original text when parsing fails"
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
    (let [hash1 (content/hash-content sample-source)
          hash2 (content/hash-content sample-source-no-docstring)]

      (assertions
        "returns hex string"
        (string? hash1) => true

        "docstring changes don't affect hash"
        hash1 => hash2)))

  (behavior "ignores formatting differences"
    (let [formatted "(defn foo [x]\n  (* x 2))"
          compact   "(defn foo [x] (* x 2))"
          hash1     (content/hash-content formatted)
          hash2     (content/hash-content compact)]

      (assertions
        "formatting doesn't affect hash"
        hash1 => hash2)))

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
