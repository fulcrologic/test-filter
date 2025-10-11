(ns com.fulcrologic.test-filter.cache
  "Persistence and caching of symbol graphs.

  Two-cache architecture:
  1. Analysis Cache (.test-filter-cache.edn) - Ephemeral snapshot of current state
  2. Success Cache (.test-filter-success.edn) - Persistent baseline of verified symbols"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [taoensso.tufte :refer [p]])
  (:import (java.time Instant)))

;; -----------------------------------------------------------------------------
;; Analysis Cache (Ephemeral)
;; -----------------------------------------------------------------------------

(def ^:dynamic *cache-file* ".test-filter-cache.edn")

(defn cache-path
  "Returns the path to the analysis cache file."
  []
  *cache-file*)

(defn save-graph!
  "Persists a symbol graph to the analysis cache (completely overwrites).

  Analysis cache structure:
  {:analyzed-at \"2024-...\"
   :paths [\"src/main\" \"src/test\"]
   :nodes {...}
   :edges [...]
   :files {\"src/foo.clj\" {:symbols [...]}}
   :content-hashes {symbol -> SHA256-hex-string}
   :reverse-index {symbol -> #{symbols-that-depend-on-it}} (optional)}"
  ([symbol-graph content-hashes paths]
   (save-graph! symbol-graph content-hashes paths nil))
  ([symbol-graph content-hashes paths reverse-index]
   (p `save-graph!
     (let [cache-data (cond-> {:analyzed-at    (str (Instant/now))
                               :paths          paths
                               :nodes          (:nodes symbol-graph)
                               :edges          (:edges symbol-graph)
                               :files          (:files symbol-graph)
                               :content-hashes content-hashes}
                        reverse-index (assoc :reverse-index reverse-index))
           cache-file (cache-path)]
       (spit cache-file (pr-str cache-data))
       cache-data))))

(defn load-graph
  "Loads the analysis cache from disk.
  Returns nil if cache doesn't exist or is invalid."
  []
  (p `load-graph
    (let [cache-file (cache-path)]
      (when (.exists (io/file cache-file))
        (try
          (edn/read-string (slurp cache-file))
          (catch Exception e
            (println "Warning: Failed to load analysis cache:" (.getMessage e))
            nil))))))

(defn invalidate-cache!
  "Deletes the analysis cache file."
  []
  (let [cache-file (io/file (cache-path))]
    (when (.exists cache-file)
      (.delete cache-file)
      true)))

;; -----------------------------------------------------------------------------
;; Success Cache (Persistent)
;; -----------------------------------------------------------------------------

(def ^:dynamic *success-cache-file* ".test-filter-success.edn")

(defn success-cache-path
  "Returns the path to the success cache file."
  []
  *success-cache-file*)

(defn load-success-cache
  "Loads the success cache from disk.

  Returns a map of symbol -> content-hash representing the last verified state.
  Returns empty map if cache doesn't exist.

  Note: Symbols are stored as strings to avoid EDN parsing issues with symbols
  containing special characters like :: (which fulcro-spec generates)."
  []
  (p `load-success-cache
    (let [cache-file (success-cache-path)]
      (if (.exists (io/file cache-file))
        (try
          (let [raw-data (edn/read-string (slurp cache-file))]
            ;; Convert string keys back to symbols
            (into {}
              (map (fn [[k v]]
                     (if (string? k)
                       [(symbol k) v]
                       [k v]))
                raw-data)))
          (catch Exception e
            (println "Warning: Failed to load success cache:" (.getMessage e))
            {}))
        {}))))

(defn save-success-cache!
  "Saves the success cache to disk.

  cache-data should be a map of symbol -> content-hash.

  Note: Symbols are converted to strings before saving to avoid EDN parsing
  issues with symbols containing special characters like :: (which fulcro-spec generates)."
  [cache-data]
  (p `save-success-cache!
    (let [cache-file   (success-cache-path)
          ;; Convert symbol keys to strings to avoid EDN parsing issues
          string-keyed (into {}
                         (map (fn [[k v]]
                                [(str k) v])
                           cache-data))]
      (spit cache-file (pr-str string-keyed))
      cache-data)))

(defn update-success-cache!
  "Updates the success cache by merging new verified hashes.

  verified-hashes is a map of symbol -> hash to merge into the success cache."
  [verified-hashes]
  (let [current (load-success-cache)
        updated (merge current verified-hashes)]
    (save-success-cache! updated)))

(defn invalidate-success-cache!
  "Deletes the success cache file."
  []
  (let [cache-file (io/file (success-cache-path))]
    (when (.exists cache-file)
      (.delete cache-file)
      true)))

(defn invalidate-all-caches!
  "Deletes both analysis and success cache files."
  []
  (invalidate-cache!)
  (invalidate-success-cache!))

;; -----------------------------------------------------------------------------
;; Cache Status
;; -----------------------------------------------------------------------------

(defn cache-status
  "Returns information about the cache files."
  []
  (let [analysis-file    (io/file (cache-path))
        success-file     (io/file (success-cache-path))
        analysis-exists? (.exists analysis-file)
        success-exists?  (.exists success-file)]
    {:analysis-cache {:exists?       analysis-exists?
                      :path          (cache-path)
                      :size          (when analysis-exists? (.length analysis-file))
                      :last-modified (when analysis-exists?
                                       (java.util.Date. (.lastModified analysis-file)))}
     :success-cache  {:exists?       success-exists?
                      :path          (success-cache-path)
                      :size          (when success-exists? (.length success-file))
                      :last-modified (when success-exists?
                                       (java.util.Date. (.lastModified success-file)))}}))

(comment
  ;; Example usage

  ;; Load analysis cache
  (def graph (load-graph))

  ;; Load success cache
  (def success (load-success-cache))

  ;; Update success cache with new verified hashes
  (update-success-cache! {'my.app/foo "sha256..."
                          'my.app/bar "sha256..."})

  ;; Check cache status
  (cache-status)

  ;; Clear analysis cache only
  (invalidate-cache!)

  ;; Clear both caches
  (invalidate-all-caches!))
