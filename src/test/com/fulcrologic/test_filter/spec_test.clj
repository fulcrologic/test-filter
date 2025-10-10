(ns com.fulcrologic.test-filter.spec-test
  "Example test using fulcro-spec to verify macro test detection."
  (:require [com.fulcrologic.test-filter.git :as git]
            [fulcro-spec.core :refer [=> =fn=> assertions behavior specification]]))

(specification "Git operations" :group1
  (behavior "can get current revision"
    (let [rev (git/current-revision)]
      (assertions
        "returns a string"
        rev =fn=> string?
        "has correct length"
        (count rev) => 40)))

  (behavior "can detect uncommitted changes"
    (let [result (git/has-uncommitted-changes?)]
      (assertions
        "returns a boolean"
        result =fn=> boolean?))))

(specification "Git diff operations" :group2
  (behavior "can get diff between revisions"
    (let [diff (git/git-diff "HEAD^" "HEAD")]
      (assertions
        "returns a string"
        diff =fn=> string?))))
