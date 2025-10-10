(ns com.fulcrologic.test-filter.cache-test
  "Unit tests for cache operations."
  (:require [com.fulcrologic.test-filter.cache :as cache]
            [fulcro-spec.core :refer [=> assertions behavior specification]]))

(specification "incremental-update removes deleted files" :group2
  (behavior "removes symbols from files that no longer exist"
    ;; Setup: Create a mock cached graph with references to files
    (let [mock-cache {:nodes    {'existing/fn {:symbol 'existing/fn :file "src/main/com/fulcrologic/test_filter/core.clj"}
                                 'deleted/fn  {:symbol 'deleted/fn :file "src/nonexistent/deleted.clj"}}
                      :edges    [{:from 'existing/fn :to 'deleted/fn :file "src/main/com/fulcrologic/test_filter/core.clj"}]
                      :files    {"src/main/com/fulcrologic/test_filter/core.clj" {:symbols ['existing/fn]}
                                 "src/nonexistent/deleted.clj"                   {:symbols ['deleted/fn]}}
                      :revision "abc123"}]

      ;; Run: incremental update with no git changes (empty set)
      ;; This should still detect and remove the deleted file
      (let [updated (cache/incremental-update mock-cache #{})]

        ;; Assert
        (assertions
          "deleted file removed from :files map"
          (contains? (:files updated) "src/nonexistent/deleted.clj") => false

          "symbol from deleted file removed from :nodes"
          (contains? (:nodes updated) 'deleted/fn) => false

          "edges referencing deleted symbol are removed"
          (empty? (filter #(or (= (:from %) 'deleted/fn)
                             (= (:to %) 'deleted/fn))
                    (:edges updated))) => true

          "existing file remains in :files map"
          (contains? (:files updated) "src/main/com/fulcrologic/test_filter/core.clj") => true

          "symbol from existing file remains in :nodes"
          (contains? (:nodes updated) 'existing/fn) => true)))))

(specification "incremental-update handles both changed and deleted" :group3
  (behavior "processes both changed files and deleted files correctly"
    ;; Setup: Create a more complex mock with changed and deleted files
    (let [mock-cache    {:nodes    {'unchanged/fn {:symbol 'unchanged/fn :file "src/main/com/fulcrologic/test_filter/analyzer.clj"}
                                    'changed/fn   {:symbol 'changed/fn :file "src/main/com/fulcrologic/test_filter/core.clj"}
                                    'deleted/fn   {:symbol 'deleted/fn :file "src/nonexistent/deleted.clj"}}
                         :edges    [{:from 'unchanged/fn :to 'changed/fn}
                                    {:from 'changed/fn :to 'deleted/fn}]
                         :files    {"src/main/com/fulcrologic/test_filter/analyzer.clj" {:symbols ['unchanged/fn]}
                                    "src/main/com/fulcrologic/test_filter/core.clj"     {:symbols ['changed/fn]}
                                    "src/nonexistent/deleted.clj"                       {:symbols ['deleted/fn]}}
                         :revision "abc123"}
          ;; Simulate that core.clj was changed in git
          changed-files #{"src/main/com/fulcrologic/test_filter/core.clj"}]

      ;; Run: incremental update
      (let [updated (cache/incremental-update mock-cache changed-files)]

        ;; Assert
        (assertions
          "deleted file removed"
          (contains? (:files updated) "src/nonexistent/deleted.clj") => false

          "symbol from deleted file removed"
          (contains? (:nodes updated) 'deleted/fn) => false

          "unchanged file remains"
          (contains? (:files updated) "src/main/com/fulcrologic/test_filter/analyzer.clj") => true

          "symbol from unchanged file remains"
          (contains? (:nodes updated) 'unchanged/fn) => true)))))

(specification "incremental-update with no changes" :group1
  (behavior "only updates revision when there are no changes"
    ;; Setup: Create mock cache
    (let [mock-cache {:nodes    {'existing/fn {:symbol 'existing/fn :file "src/main/com/fulcrologic/test_filter/core.clj"}}
                      :edges    []
                      :files    {"src/main/com/fulcrologic/test_filter/core.clj" {:symbols ['existing/fn]}}
                      :revision "abc123"}]

      ;; Run: incremental update with no changes
      (let [updated (cache/incremental-update mock-cache #{})]

        ;; Assert: structure preserved when no files changed or deleted
        (assertions
          "graph structure remains unchanged"
          (dissoc updated :revision :analyzed-at) => (dissoc mock-cache :revision :analyzed-at))))))
