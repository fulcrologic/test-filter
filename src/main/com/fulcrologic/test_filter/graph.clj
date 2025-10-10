(ns com.fulcrologic.test-filter.graph
  "Graph operations for dependency analysis and test selection."
  (:require [loom.graph :as lg]
            [loom.alg :as alg]
            [clojure.set :as set]))

;; -----------------------------------------------------------------------------
;; Graph Construction
;; -----------------------------------------------------------------------------

(defn build-dependency-graph
  "Builds a directed graph from symbol nodes and usage edges.

  The graph represents 'uses' relationships:
    A -> B means 'A uses B'

  We'll traverse backwards from tests to find dependencies."
  [{:keys [nodes edges]}]
  (let [;; Create directed graph with all nodes
        g (apply lg/digraph (keys nodes))
        ;; Add edges (from uses to)
        g-with-edges (reduce (fn [g {:keys [from to]}]
                               (if (and from to)
                                 (lg/add-edges g [from to])
                                 g))
                             g
                             edges)]
    g-with-edges))

;; -----------------------------------------------------------------------------
;; Dependency Walking
;; -----------------------------------------------------------------------------

(defn transitive-dependencies
  "Returns all transitive dependencies of a symbol.

  Given a symbol, walks the graph backwards (in the direction of 'uses')
  to find all symbols it transitively depends on.

  Example: If test-foo uses handler, and handler uses db-query,
           returns #{handler db-query}"
  [graph symbol]
  (when (lg/has-node? graph symbol)
    ;; In loom, we need to get successors (outgoing edges) since we modeled
    ;; as 'A uses B' means edge A -> B
    (set (alg/bf-traverse graph symbol))))

(defn symbols-with-dependents
  "Returns a map of symbol -> #{symbols-that-depend-on-it}.

  This is useful for finding which tests are affected by a change."
  [graph]
  (reduce (fn [acc node]
            (let [deps (transitive-dependencies graph node)]
              (reduce (fn [m dep]
                        (update m dep (fnil conj #{}) node))
                      acc
                      deps)))
          {}
          (lg/nodes graph)))

;; -----------------------------------------------------------------------------
;; Test Selection
;; -----------------------------------------------------------------------------

(defn find-affected-tests
  "Given a set of changed symbols and a dependency graph,
  returns the set of test symbols that transitively depend on any changed symbol.

  Handles integration tests specially:
  - If a test has :test-targets metadata, only run if those targets changed
  - If a test is marked :integration? but has no targets, run conservatively (always)
  - Otherwise, use transitive dependency analysis

  Args:
    graph - loom directed graph of symbol dependencies
    test-symbols - collection of test symbols or [symbol node-data] pairs
    changed-symbols - set of symbols that have changed
    symbol-graph - original symbol graph with node metadata

  Returns:
    Set of test symbols that need to run"
  [graph test-symbols changed-symbols symbol-graph]
  (let [;; Normalize test-symbols to pairs if needed
        test-pairs (if (and (seq test-symbols)
                            (not (sequential? (first test-symbols))))
                     (map (fn [sym] [sym (get-in symbol-graph [:nodes sym])]) test-symbols)
                     test-symbols)

        ;; For each test, determine if it should run
        affected (for [[test-sym node] test-pairs
                       :let [metadata (:metadata node)
                             test-targets (:test-targets metadata)
                             integration? (:integration? metadata)]]
                   (cond
                     ;; If test has explicit targets, check if any changed
                     test-targets
                     (when (seq (set/intersection test-targets changed-symbols))
                       test-sym)

                     ;; If integration test without targets, run conservatively
                     ;; (we can't determine dependencies accurately)
                     integration?
                     test-sym

                     ;; Otherwise, use transitive dependency analysis
                     :else
                     (let [deps (transitive-dependencies graph test-sym)]
                       (when (seq (set/intersection deps changed-symbols))
                         test-sym))))]
    (set (filter some? affected))))

;; -----------------------------------------------------------------------------
;; Graph Utilities
;; -----------------------------------------------------------------------------

(defn graph-stats
  "Returns statistics about the dependency graph."
  [graph]
  {:node-count (count (lg/nodes graph))
   :edge-count (count (lg/edges graph))
   :nodes (lg/nodes graph)})

(comment
  ;; Example usage with analyzer:
  (require '[com.fulcrologic.test-filter.analyzer :as analyzer])

  (def analysis (analyzer/run-analysis {:paths ["src/main"]}))
  (def symbol-graph (analyzer/build-symbol-graph analysis))
  (def graph (build-dependency-graph symbol-graph))

  (graph-stats graph)

  ;; Find what test-foo depends on
  (transitive-dependencies graph 'test-filter.analyzer-test/test-foo)

  ;; Find affected tests when analyzer/run-analysis changes
  (def test-syms (set (map first (analyzer/find-test-vars symbol-graph))))
  (find-affected-tests graph test-syms #{'test-filter.analyzer/run-analysis}))
