(ns com.fulcrologic.test-filter.git-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.fulcrologic.test-filter.git :as git]))

(deftest test-current-revision
  (testing "Getting current git revision"
    (let [rev (git/current-revision)]
      (is (string? rev))
      (is (= 40 (count rev)))
      (is (re-matches #"[0-9a-f]{40}" rev)))))

(deftest test-has-uncommitted-changes
  (testing "Detecting uncommitted changes"
    (let [result (git/has-uncommitted-changes?)]
      (is (boolean? result)))))

(deftest test-resolve-revision
  (testing "Resolving revision references"
    (let [head (git/resolve-revision "HEAD")]
      (is (string? head))
      (is (= 40 (count head))))

    (testing "HEAD^ resolves to parent commit"
      (let [head (git/resolve-revision "HEAD")
            parent (git/resolve-revision "HEAD^")]
        (is (not= head parent))
        (is (= 40 (count parent)))))))
