(ns com.fulcrologic.test-filter.git-test
  (:require [com.fulcrologic.test-filter.git :as git]
            [fulcro-spec.core :refer [=> assertions behavior component specification]]))

(specification "current-revision" :group1
  (behavior "gets current git revision"
    (let [rev (git/current-revision)]

      (assertions
        "returns a string"
        (string? rev) => true

        "is 40 characters long"
        (count rev) => 40

        "matches SHA-1 pattern"
        (re-matches #"[0-9a-f]{40}" rev) => rev))))

(specification "has-uncommitted-changes?" :group2
  (behavior "detects uncommitted changes"
    (let [result (git/has-uncommitted-changes?)]

      (assertions
        "returns a boolean"
        (boolean? result) => true))))

(specification "resolve-revision" :group3
  (behavior "resolves revision references"
    (let [head (git/resolve-revision "HEAD")]

      (assertions
        "HEAD returns a string"
        (string? head) => true

        "HEAD is 40 characters"
        (count head) => 40))

    (component "HEAD^ resolves to parent commit"
      (let [head   (git/resolve-revision "HEAD")
            parent (git/resolve-revision "HEAD^")]

        (assertions
          "parent is different from HEAD"
          (not= head parent) => true

          "parent is 40 characters"
          (count parent) => 40)))))
