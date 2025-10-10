(ns com.fulcrologic.test-filter.utils-test
  "Tests for utility functions from CLJC file."
  (:require [com.fulcrologic.test-filter.utils :as utils]
            [fulcro-spec.core :refer [=> assertions behavior specification]]))

(specification "normalize-path" :group1
  (behavior "normalizes path separators"
    (assertions
      "removes double slashes from beginning"
      (utils/normalize-path "/foo//bar") => "/foo/bar"

      "removes multiple slashes"
      (utils/normalize-path "/a///b//c") => "/a/b/c")))

(specification "file-extension" :group2
  (behavior "extracts file extension"
    (assertions
      "gets .clj extension"
      (utils/file-extension "foo.clj") => "clj"

      "gets .cljc extension"
      (utils/file-extension "bar.cljc") => "cljc"

      "returns nil for no extension"
      (utils/file-extension "no-extension") => nil)))

(specification "join-paths" :group3
  (behavior "joins path segments"
    (assertions
      "joins three segments"
      (utils/join-paths "foo" "bar" "baz") => "foo/bar/baz"

      "joins multiple segments"
      (utils/join-paths "a" "b" "c") => "a/b/c")))
