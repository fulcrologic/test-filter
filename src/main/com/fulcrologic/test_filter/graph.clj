(ns com.fulcrologic.test-filter.graph
  "Graph operations for dependency analysis and test selection."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [loom.alg :as alg]
            [loom.graph :as lg]))

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
        g            (apply lg/digraph (keys nodes))
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
        affected   (for [[test-sym node] test-pairs
                         :let [metadata     (:metadata node)
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
   :nodes      (lg/nodes graph)})

(defn trace-test-dependencies
  "Returns a map showing the dependency chain from tests to changed symbols.

  For each affected test, finds one shortest path to each changed symbol it depends on.
  Useful for understanding why a test was selected.

  Args:
    graph - loom directed graph of symbol dependencies
    test-symbols - collection of test symbols to trace
    changed-symbols - set of symbols that have changed

  Returns:
    Map of {test-symbol -> {changed-symbol -> [path-to-changed]}}
    where path is a vector showing the chain: [test intermediate1 ... changed-symbol]

  Example output:
    {'my.app-test/foo-test {'my.app/h [my.app-test/foo-test my.app/f my.app/g my.app/h]}}"
  [graph test-symbols changed-symbols]
  (into {}
    (for [test-sym test-symbols
          :let [deps             (transitive-dependencies graph test-sym)
                relevant-changes (set/intersection deps changed-symbols)]
          :when (seq relevant-changes)]
      [test-sym
       (into {}
         (for [changed-sym relevant-changes
               :let [path (alg/bf-path graph test-sym changed-sym)]
               :when path]
           [changed-sym (vec path)]))])))

(defn format-trace
  "Formats a trace map into a human-readable string.

  Options:
  - :style - :compact (default) or :detailed
    - :compact: 'test-foo [f g h]'
    - :detailed: 'test-foo -> f -> g -> h (changed)'

  Args:
    trace-map - output from trace-test-dependencies
    opts - formatting options

  Returns:
    String representation of the trace"
  [trace-map & {:keys [style] :or {style :compact}}]
  (let [lines (for [[test-sym changes] (sort-by (comp str first) trace-map)]
                (if (= style :detailed)
                  ;; Detailed: show each change on separate line with full path
                  (str test-sym ":\n"
                    (str/join "\n"
                      (for [[changed-sym path] (sort-by (comp str first) changes)]
                        (str "  " (str/join " -> " path) " (changed)"))))
                  ;; Compact: show all changed symbols in a flat list
                  (let [all-changes  (set (mapcat second changes))
                        ;; Get unique symbols in paths (excluding the test itself)
                        path-symbols (into (sorted-set)
                                       (mapcat (fn [[_ path]]
                                                 (rest path))
                                         changes))]
                    (str test-sym " " (vec path-symbols)))))]
    (str/join "\n" lines)))

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
