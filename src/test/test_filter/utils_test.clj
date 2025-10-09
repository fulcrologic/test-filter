(ns test-filter.utils-test
  "Tests for utility functions from CLJC file."
  (:require [clojure.test :refer [deftest is testing]]
            [test-filter.utils :as utils]))

(deftest test-normalize-path
  (testing "Path normalization"
    (is (= "/foo/bar" (utils/normalize-path "/foo//bar")))
    (is (= "/a/b/c" (utils/normalize-path "/a///b//c")))))

(deftest test-file-extension
  (testing "File extension extraction"
    (is (= "clj" (utils/file-extension "foo.clj")))
    (is (= "cljc" (utils/file-extension "bar.cljc")))
    (is (nil? (utils/file-extension "no-extension")))))

(deftest test-join-paths
  (testing "Path joining"
    (is (= "foo/bar/baz" (utils/join-paths "foo" "bar" "baz")))
    (is (= "a/b/c" (utils/join-paths "a" "b" "c")))))
