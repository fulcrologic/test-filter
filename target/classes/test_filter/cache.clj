(ns test-filter.cache
  "Persistence and caching of symbol graphs with git revision tracking."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [test-filter.analyzer :as analyzer]
            [test-filter.git :as git])
  (:import [java.time Instant]))

(def ^:dynamic *cache-file* ".test-filter-cache.edn")

(defn cache-path
  "Returns the path to the cache file, relative to the project root."
  []
  *cache-file*)

(defn save-graph!
  "Persists a symbol graph to disk with revision metadata.
  
  Graph structure:
  {:revision \"abc123...\"
   :analyzed-at \"2024-...\"  ; ISO-8601 string
   :nodes {...}
   :edges [...]
   :files {\"src/foo.clj\" {:symbols [...] :revision \"abc123\"}}}"
  [symbol-graph revision]
  (let [cache-data {:revision revision
                    :analyzed-at (str (Instant/now)) ; Convert to string for EDN
                    :nodes (:nodes symbol-graph)
                    :edges (:edges symbol-graph)
                    :files (:files symbol-graph)}
        cache-file (cache-path)]
    (spit cache-file (pr-str cache-data))
    cache-data))

(defn load-graph
  "Loads a cached symbol graph from disk.
  Returns nil if cache doesn't exist or is invalid."
  []
  (let [cache-file (cache-path)]
    (when (.exists (io/file cache-file))
      (try
        (edn/read-string (slurp cache-file))
        (catch Exception e
          (println "Warning: Failed to load cache:" (.getMessage e))
          nil)))))

(defn cache-valid?
  "Checks if cached graph is valid for the current git revision.
  Returns true if cache exists and matches current revision."
  [cached-graph]
  (when cached-graph
    (let [current-rev (git/current-revision)
          cached-rev (:revision cached-graph)]
      (= current-rev cached-rev))))

(defn changed-files-since-cache
  "Returns set of files that have changed since the cached revision."
  [cached-graph]
  (when-let [cached-rev (:revision cached-graph)]
    (let [current-rev (git/current-revision)]
      (if (= cached-rev current-rev)
        #{}
        (git/changed-files cached-rev current-rev)))))

(defn incremental-update
  "Updates a cached graph by re-analyzing only changed files.
  
  Steps:
  1. Find files changed since cache
  2. Re-analyze those files
  3. Remove old symbols from those files
  4. Add new symbols from analysis
  5. Update revision
  
  Returns updated graph."
  [{:keys [nodes edges files] :as cached-graph} changed-file-set]
  (if (empty? changed-file-set)
    ;; No changes, just update revision
    (assoc cached-graph :revision (git/current-revision)
           :analyzed-at (Instant/now))
    ;; Re-analyze changed files
    (let [;; Remove symbols from changed files
          removed-symbols (set (mapcat :symbols
                                       (vals (select-keys files changed-file-set))))
          cleaned-nodes (apply dissoc nodes removed-symbols)
          cleaned-edges (remove #(or (contains? removed-symbols (:from %))
                                     (contains? removed-symbols (:to %)))
                                edges)

          ;; Re-analyze changed files
          paths (vec changed-file-set)
          new-analysis (analyzer/run-analysis {:paths paths})
          new-graph (analyzer/build-symbol-graph new-analysis)

          ;; Merge new data
          merged-nodes (merge cleaned-nodes (:nodes new-graph))
          merged-edges (concat cleaned-edges (:edges new-graph))
          merged-files (merge (apply dissoc files changed-file-set)
                              (:files new-graph))]

      {:revision (git/current-revision)
       :analyzed-at (Instant/now)
       :nodes merged-nodes
       :edges merged-edges
       :files merged-files})))

(defn get-or-build-graph
  "Gets cached graph if valid, otherwise builds a fresh one.
  
  Options:
  - :force - Force rebuild even if cache is valid
  - :incremental - Try incremental update if cache is stale (default true)
  - :paths - Paths to analyze (default [\"src\"])
  
  Returns symbol graph with metadata."
  [& {:keys [force incremental paths]
      :or {incremental true
           paths ["src"]}}]
  (let [cached (load-graph)
        current-rev (git/current-revision)]

    (cond
      ;; Force rebuild requested
      force
      (let [analysis (analyzer/run-analysis {:paths paths})
            graph (analyzer/build-symbol-graph analysis)]
        (save-graph! graph current-rev)
        graph)

      ;; Cache valid, use it
      (cache-valid? cached)
      cached

      ;; Cache exists but stale, try incremental update
      (and incremental cached)
      (let [changed (changed-files-since-cache cached)
            updated (incremental-update cached changed)]
        (save-graph! updated (:revision updated))
        updated)

      ;; No cache or incremental disabled, full rebuild
      :else
      (let [analysis (analyzer/run-analysis {:paths paths})
            graph (analyzer/build-symbol-graph analysis)]
        (save-graph! graph current-rev)
        graph))))

(defn invalidate-cache!
  "Deletes the cache file."
  []
  (let [cache-file (io/file (cache-path))]
    (when (.exists cache-file)
      (.delete cache-file)
      true)))

(comment
  ;; Example usage

  ;; Get or build graph (uses cache if valid)
  (def graph (get-or-build-graph))

  ;; Force rebuild
  (def graph (get-or-build-graph :force true))

  ;; Disable incremental updates
  (def graph (get-or-build-graph :incremental false))

  ;; Check cache status
  (let [cached (load-graph)]
    (if (cache-valid? cached)
      (println "Cache is valid")
      (println "Cache is stale or missing")))

  ;; See what files changed
  (changed-files-since-cache (load-graph))

  ;; Invalidate cache
  (invalidate-cache!))
