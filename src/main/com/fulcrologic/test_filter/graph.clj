(ns com.fulcrologic.test-filter.graph
  "Graph operations for dependency analysis and test selection."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.test-filter.analyzer :as analyzer]
    [loom.alg :as alg]
    [loom.graph :as lg]
    [taoensso.tufte :refer [p]]))

;; -----------------------------------------------------------------------------
;; Graph Construction
;; -----------------------------------------------------------------------------

(defn build-dependency-graph
  "Builds a directed graph from symbol nodes and usage edges.

  The graph represents 'uses' relationships:
    A -> B means 'A uses B'

  We'll traverse backwards from tests to find dependencies."
  [{:keys [nodes edges]}]
  (p `build-dependency-graph
    (let [;; Create directed graph with all nodes
          g            (apply lg/digraph (keys nodes))
          ;; Add edges (from uses to)
          g-with-edges (reduce (fn [g {:keys [from to]}]
                                 (if (and from to)
                                   (lg/add-edges g [from to])
                                   g))
                         g
                         edges)]
      g-with-edges)))

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
  (p `transitive-dependencies
    (when (lg/has-node? graph symbol)
      ;; In loom, we need to get successors (outgoing edges) since we modeled
      ;; as 'A uses B' means edge A -> B
      (set (alg/bf-traverse graph symbol)))))

(defn symbols-with-dependents
  "Returns a map of symbol -> #{symbols-that-depend-on-it}.

  This is useful for finding which tests are affected by a change.

  OPTIMIZED: Uses topological sort and dynamic programming to compute
  the reverse dependency index efficiently in O(V + E) time instead of
  O(V * E) by calling transitive-dependencies once per node.

  Algorithm:
  1. Process nodes in REVERSE topological order (leaves first, roots last)
  2. For each node, compute transitive deps from immediate successors
  3. Build reverse map by recording each node as a dependent of its deps"
  [graph]
  (p `symbols-with-dependents
    (let [;; Get nodes in topological order then reverse it
          ;; Process leaves (no dependencies) first, then work up to roots
          ;; If there are cycles, topsort returns nil, so fall back to node list
          topo-order     (or (alg/topsort graph) (lg/nodes graph))
          reverse-order  (reverse topo-order)

          ;; Phase 1: Build transitive dependency map using dynamic programming
          ;; Process in reverse topo order so dependencies are computed before their dependents
          transitive-map (p ::build-transitive-map
                           (reduce
                             (fn [trans-map node]
                               (let [;; Get immediate successors (things this node directly uses)
                                     successors (set (lg/successors graph node))
                                     ;; Transitive deps = successors + their transitive deps (already computed)
                                     trans-deps (into successors
                                                  (mapcat #(get trans-map % #{}) successors))]
                                 (assoc trans-map node trans-deps)))
                             {}
                             reverse-order))

          ;; Phase 2: Invert the map to get reverse dependencies
          ;; For each node and its transitive deps, record node as a dependent
          reverse-map    (p ::build-reverse-map
                           (reduce
                             (fn [rev-map [node deps]]
                               (reduce (fn [m dep]
                                         (update m dep (fnil conj #{}) node))
                                 rev-map
                                 deps))
                             {}
                             transitive-map))]
      reverse-map)))

;; -----------------------------------------------------------------------------
;; Test Selection
;; -----------------------------------------------------------------------------

(defn find-affected-tests
  "Given a set of changed symbols and a dependency graph,
  returns the set of test symbols that transitively depend on any changed symbol.

  Handles integration tests specially:
  - If a test has :test-targets metadata, only run if those targets changed
  - If a test is marked :integration? but has no targets, run conservatively (always)
  - Otherwise, use transitive dependency analysis via reverse index

  This function is optimized to use a reverse dependency index (symbol -> tests-that-depend-on-it)
  which dramatically improves performance for large codebases.

  Args:
    graph - loom directed graph of symbol dependencies (or map with :graph and :reverse-index)
    test-symbols - collection of test symbols or [symbol node-data] pairs
    changed-symbols - set of symbols that have changed
    symbol-graph - original symbol graph with node metadata

  Returns:
    Set of test symbols that need to run"
  [graph test-symbols changed-symbols symbol-graph]
  (p `find-affected-tests
    (let [;; Support both raw graph and graph-with-index map
          dep-graph         (if (map? graph)
                              (or (:graph graph) graph)
                              graph)
          reverse-index     (when (map? graph) (:reverse-index graph))

          ;; Normalize test-symbols to pairs if needed
          test-pairs        (p ::test-pairs
                              (if (and (seq test-symbols)
                                    (not (sequential? (first test-symbols))))
                                (vec
                                  (pmap (fn [sym] [sym (get-in symbol-graph [:nodes sym])]) test-symbols))
                                test-symbols))

          ;; Separate tests by type for optimized handling. Use transients because could be large
          [integration-tests targeted-tests regular-tests] (p ::test-by-type
                                                             (mapv
                                                               persistent!
                                                               (reduce (fn [[int-tests tgt-tests reg-tests] [test-sym node]]
                                                                         (let [metadata     (:metadata node)
                                                                               test-targets (:test-targets metadata)
                                                                               integration? (:integration? metadata)]
                                                                           (cond
                                                                             test-targets [int-tests (conj! tgt-tests [test-sym test-targets]) reg-tests]
                                                                             integration? [(conj! int-tests test-sym) tgt-tests reg-tests]
                                                                             :else [int-tests tgt-tests (conj! reg-tests test-sym)])))
                                                                 [(transient #{}) (transient []) (transient #{})]
                                                                 test-pairs)))
          ;; Handle targeted tests
          affected-targeted (p ::affected-targets
                              (into #{}
                                (comp
                                  (filter (fn [[_test-sym targets]]
                                            (seq (set/intersection targets changed-symbols))))
                                  (map first))
                                targeted-tests))

          ;; Handle regular tests using reverse index if available
          affected-regular  (if reverse-index
                              ;; OPTIMIZED PATH: Use reverse index
                              ;; For each changed symbol, find tests that depend on it
                              (p ::reverse-index-affected
                                (let [affected-by-changes (persistent!
                                                            (reduce (fn [acc changed-sym] (reduce conj! acc (get reverse-index changed-sym #{})))
                                                              (transient #{})
                                                              changed-symbols))]
                                  ;; Intersect with actual test symbols
                                  (set/intersection affected-by-changes regular-tests)))

                              ;; FALLBACK PATH: Use old algorithm if no index
                              (p :fallback-affected
                                (into #{}
                                  (filter (fn [test-sym]
                                            (let [deps (transitive-dependencies dep-graph test-sym)]
                                              (seq (set/intersection deps changed-symbols)))))
                                  regular-tests)))]

      ;; Combine all affected tests
      (p ::final-union
        (set/union integration-tests affected-targeted affected-regular)))))

(defn find-untested-usages
  "Finds direct dependents of changed symbols that have no path to any test.

  For each changed symbol, returns the set of symbols that directly use it
  but are not covered by any test (i.e., no test transitively depends on them).

  This helps identify coverage gaps: code that depends on changed functions
  but isn't being tested.

  Args:
    graph - loom directed graph (or map with :graph and :reverse-index)
    changed-symbols - set of symbols that have changed
    symbol-graph - original symbol graph with node metadata

  Returns:
    Map of changed-symbol -> #{untested-dependent-symbols}
    Only includes entries where there are untested dependents."
  [graph changed-symbols symbol-graph]
  (p `find-untested-usages
    (let [dep-graph     (if (map? graph) (or (:graph graph) graph) graph)
          reverse-index (when (map? graph) (:reverse-index graph))
          test-symbols  (into #{}
                          (map first)
                          (analyzer/find-test-vars symbol-graph))
          ;; Helper to check if a symbol is test-related
          test-related? (fn [sym]
                          (or (test-symbols sym)
                            ;; Also exclude test namespace SYMBOLS (not test vars!)
                            ;; Namespace symbols have no namespace part and name ends with -test
                            (let [ns-str   (namespace sym)
                                  name-str (name sym)]
                              (and (nil? ns-str) (str/ends-with? name-str "-test")))))]

      (into {}
        (keep (fn [changed-sym]
                (when (lg/has-node? dep-graph changed-sym)
                  ;; Get direct dependents (predecessors, since edge A->B means "A uses B")
                  (let [direct-dependents (lg/predecessors dep-graph changed-sym)
                        ;; Filter to those with no test coverage
                        untested          (into #{}
                                            (filter (fn [dep-sym]
                                                      ;; Exclude tests and test-related symbols
                                                      (and (not (test-related? dep-sym))
                                                        ;; Check if no tests depend on this symbol
                                                        (if reverse-index
                                                          (empty? (get reverse-index dep-sym))
                                                          ;; Fallback: check all tests for paths
                                                          (not-any? (fn [test-sym]
                                                                      (let [deps (transitive-dependencies dep-graph test-sym)]
                                                                        (contains? deps dep-sym)))
                                                            test-symbols)))))
                                            direct-dependents)]
                    (when (seq untested)
                      [changed-sym untested])))))
        changed-symbols))))

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
  (p `trace-test-dependencies
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
             [changed-sym (vec path)]))]))))

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
  (p `format-trace
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
      (str/join "\n" lines))))

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
