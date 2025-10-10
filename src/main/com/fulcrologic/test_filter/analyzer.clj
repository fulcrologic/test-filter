(ns com.fulcrologic.test-filter.analyzer
  "Analyzes Clojure source code using clj-kondo to build a symbol dependency graph."
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; clj-kondo Integration
;; -----------------------------------------------------------------------------

(defn run-analysis
  "Runs clj-kondo analysis on the specified paths and returns the analysis data.

  Options:
    :paths - Vector of paths to analyze (defaults to [\"src\"])
    :config - Additional clj-kondo config map

  Returns a map with:
    :var-definitions - All defined vars with location info
    :var-usages - All var usages with caller context
    :namespace-definitions - Namespace definitions
    :namespace-usages - Namespace requires/imports"
  [{:keys [paths config]
    :or {paths ["src"]}}]
  (let [config-map (merge {:lint paths
                           :config {:output {:analysis true}}}
                          config)
        result (clj-kondo/run! config-map)]
    (if (:analysis result)
      result
      (throw (ex-info "clj-kondo analysis failed"
                      {:result result})))))

(defn integration-test?
  "Returns true if the namespace name suggests an integration test.
  Matches namespaces containing 'integration' as a segment."
  [ns-symbol]
  (boolean (re-find #"\.integration\." (str ns-symbol))))

(defn extract-test-metadata
  "Extracts test-related metadata from a var definition.

  Returns a map with:
    :test-targets - Set of symbols this test explicitly targets (from metadata)
    :integration? - True if this appears to be an integration test"
  [var-def]
  (let [meta-data (:meta var-def)
        test-targets (or (:test-targets meta-data)
                         (:test-target meta-data))
        ;; Normalize to set
        targets (cond
                  (set? test-targets) test-targets
                  (symbol? test-targets) #{test-targets}
                  (sequential? test-targets) (set test-targets)
                  :else nil)
        integration? (or (:integration meta-data)
                         (integration-test? (:ns var-def)))]
    (cond-> {}
      targets (assoc :test-targets targets)
      integration? (assoc :integration? true))))

;; Forward declarations
(declare find-macro-tests)

;; -----------------------------------------------------------------------------
;; Symbol Graph Building
;; -----------------------------------------------------------------------------

(defn extract-var-definitions
  "Extracts var definitions from clj-kondo analysis into our node format.
  Only includes :clj language definitions, filtering out :cljs from CLJC files.
  Also filters out pure .cljs files entirely."
  [analysis]
  (let [var-defs (get-in analysis [:analysis :var-definitions])
        ;; Filter to only CLJ:
        ;; - Reject if filename ends with .cljs (pure CLJS files)
        ;; - Reject if :lang is :cljs (CLJS side of CLJC files)
        ;; - Accept if :lang is nil (pure .clj files) or :clj (CLJ side of CLJC)
        clj-only (filter (fn [def]
                           (let [lang (:lang def)
                                 filename (:filename def)]
                             (and (not= :cljs lang)
                                  (not (str/ends-with? filename ".cljs")))))
                         var-defs)]
    (map (fn [{:keys [ns name filename row end-row defined-by meta] :as def}]
           (let [base-metadata (select-keys def [:private :macro :deprecated :test])
                 test-metadata (when (:test def) (extract-test-metadata def))
                 all-metadata (merge base-metadata test-metadata)]
             {:symbol (symbol (str ns) (str name))
              :type :var
              :file filename
              :line row
              :end-line end-row
              :defined-by defined-by
              :metadata all-metadata}))
         clj-only)))

(defn extract-namespace-definitions
  "Extracts namespace definitions from clj-kondo analysis.
  Only includes :clj language namespaces, filtering out :cljs from CLJC files.
  Also filters out pure .cljs files entirely."
  [analysis]
  (let [ns-defs (get-in analysis [:analysis :namespace-definitions])
        ;; Filter to only CLJ (same logic as var-definitions)
        clj-only (filter (fn [def]
                           (let [lang (:lang def)
                                 filename (:filename def)]
                             (and (not= :cljs lang)
                                  (not (str/ends-with? filename ".cljs")))))
                         ns-defs)]
    (map (fn [{:keys [name filename row] :as def}]
           {:symbol name
            :type :namespace
            :file filename
            :line row
            :metadata (select-keys def [:deprecated :doc])})
         clj-only)))

(defn extract-var-usages
  "Extracts var usage edges from clj-kondo analysis.
  Returns edges showing which symbols use which other symbols.
  Only includes :clj language usages, filtering out :cljs from CLJC files.
  Also filters out pure .cljs files entirely."
  [analysis]
  (let [var-usages (get-in analysis [:analysis :var-usages])
        ;; Filter to only CLJ (excludes js/ and other cljs refs)
        clj-only (filter (fn [usage]
                           (let [lang (:lang usage)
                                 filename (:filename usage)]
                             (and (not= :cljs lang)
                                  (not (str/ends-with? filename ".cljs")))))
                         var-usages)]
    (map (fn [{:keys [from to name from-var filename row]}]
           {:from (if from-var
                    (symbol (str from) (str from-var))
                    ;; For top-level code (like macro-based tests), use the namespace
                    (symbol (str from)))
            :to (symbol (str to) (str name))
            :file filename
            :line row})
         clj-only)))

(defn build-symbol-graph
  "Builds a complete symbol graph from clj-kondo analysis.

  Returns:
    {:nodes {symbol -> node-data}
     :edges [{:from :to :file :line}]
     :files {file-path -> {:symbols [symbols]}}
     :analysis - original analysis for reference}"
  [analysis]
  (let [var-nodes (extract-var-definitions analysis)
        ns-nodes (extract-namespace-definitions analysis)
        macro-test-nodes (find-macro-tests analysis)
        all-nodes (concat var-nodes ns-nodes (map second macro-test-nodes))
        edges (vec (extract-var-usages analysis))

        ;; Build files map: file-path -> {:symbols [symbols-in-file]}
        files-map (reduce (fn [acc node]
                            (let [file (:file node)
                                  sym (:symbol node)]
                              (update acc file
                                      (fn [file-data]
                                        (update (or file-data {:symbols []})
                                                :symbols conj sym)))))
                          {}
                          all-nodes)]
    {:nodes (into {} (map (fn [node] [(:symbol node) node]) all-nodes))
     :edges edges
     :files files-map
     :analysis analysis}))

;; -----------------------------------------------------------------------------
;; Test Identification
;; -----------------------------------------------------------------------------

(defn test-var?
  "Returns true if the node represents a test var."
  [node]
  (or (= 'clojure.test/deftest (:defined-by node))
      (get-in node [:metadata :test])))

(defn find-test-vars
  "Returns all test vars from the symbol graph."
  [symbol-graph]
  (filter (fn [[_sym node]]
            (test-var? node))
          (:nodes symbol-graph)))

(def default-test-macros
  "Default set of macro symbols that define tests."
  '#{fulcro-spec.core/specification})

(defn find-macro-tests
  "Find tests defined by macros like fulcro-spec's specification.

  Returns a map of pseudo-test-vars in the same format as regular test vars,
  using the namespace as the test symbol since macro calls don't create var definitions.

  Args:
    analysis - clj-kondo analysis result
    test-macros - Set of qualified macro symbols to treat as test definers
                  (defaults to fulcro-spec.core/specification)"
  ([analysis] (find-macro-tests analysis default-test-macros))
  ([analysis test-macros]
   (let [var-usages (get-in analysis [:analysis :var-usages])
         macro-calls (filter (fn [{:keys [to name]}]
                               (contains? test-macros
                                          (symbol (str to) (str name))))
                             var-usages)
         ;; Group by namespace since we don't have individual test names
         by-namespace (group-by :from macro-calls)]
     ;; Create one test entry per namespace containing macro tests
     (map (fn [[ns-sym calls]]
            (let [first-call (first calls)]
              [ns-sym
               {:symbol ns-sym
                :type :test
                :file (:filename first-call)
                :line (:row first-call)
                :end-line (:end-row first-call)
                :defined-by 'macro-test
                :metadata {:test true
                           :macro-test true
                           :test-count (count calls)}}]))
          by-namespace))))

(comment
  ;; Example usage:
  (def analysis (run-analysis {:paths ["src/main"]}))
  (def graph (build-symbol-graph analysis))
  (keys graph)
  ;; => (:nodes :edges)

  (def test-vars (find-test-vars graph)))
