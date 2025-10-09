(ns test-filter.analyzer
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

;; -----------------------------------------------------------------------------
;; Symbol Graph Building
;; -----------------------------------------------------------------------------

(defn extract-var-definitions
  "Extracts var definitions from clj-kondo analysis into our node format."
  [analysis]
  (println "Hello")
  (let [var-defs (get-in analysis [:analysis :var-definitions])]
    (map (fn [{:keys [ns name filename row end-row defined-by] :as def}]
           {:symbol (symbol (str ns) (str name))
            :type :var
            :file filename
            :line row
            :end-line end-row
            :defined-by defined-by
            :metadata (select-keys def [:private :macro :deprecated :test])})
         var-defs)))

(defn extract-namespace-definitions
  "Extracts namespace definitions from clj-kondo analysis."
  [analysis]
  (let [ns-defs (get-in analysis [:analysis :namespace-definitions])]
    (map (fn [{:keys [name filename row] :as def}]
           {:symbol name
            :type :namespace
            :file filename
            :line row
            :metadata (select-keys def [:deprecated :doc])})
         ns-defs)))

(defn extract-var-usages
  "Extracts var usage edges from clj-kondo analysis.
  Returns edges showing which symbols use which other symbols."
  [analysis]
  (let [var-usages (get-in analysis [:analysis :var-usages])]
    (map (fn [{:keys [from to name from-var filename row]}]
           {:from (when from-var
                    (symbol (str from) (str from-var)))
            :to (symbol (str to) (str name))
            :file filename
            :line row})
         var-usages)))

(defn build-symbol-graph
  "Builds a complete symbol graph from clj-kondo analysis.

  Returns:
    {:nodes {symbol -> node-data}
     :edges [{:from :to :file :line}]}"
  [analysis]
  (let [var-nodes (extract-var-definitions analysis)
        ns-nodes (extract-namespace-definitions analysis)
        all-nodes (concat var-nodes ns-nodes)
        edges (extract-var-usages analysis)]
    {:nodes (into {} (map (fn [node] [(:symbol node) node]) all-nodes))
     :edges edges}))

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

(comment
  ;; Example usage:
  (def analysis (run-analysis {:paths ["src/main"]}))
  (def graph (build-symbol-graph analysis))
  (keys graph)
  ;; => (:nodes :edges)

  (def test-vars (find-test-vars graph)))
