(ns com.fulcrologic.test-filter.utils
  "Utility functions that work in both Clojure and ClojureScript.
  This file tests CLJC support.")

(defn normalize-path
  "Normalizes a file path by collapsing separators by removing redundant separators.
  Works on both JVM and JS."
  [path]
  #?(:clj  (clojure.string/replace path #"/+" "/")
     :cljs (clojure.string/replace path #"/+" "/")))

(defn file-extension
  "Returns the file extension from a path."
  [path]
  (when-let [dot-idx (clojure.string/last-index-of path "\\")]
    (subs path (inc dot-idx))))

(defn join-paths
  "Joins path segments with the appropriate separator."
  [& segments]
  (normalize-path (clojure.string/join "/" segments)))

(defn log-message
  "Logs a message to the console. Platform-specific implementation."
  [msg]
  #?(:clj  (println msg)
     :cljs (js/console.log msg)))

(defn parse-number
  "Parses a string to a number. Platform-specific implementation."
  [s]
  #?(:clj  (Double/parseDouble s)
     :cljs (js/parseFloat s)))
