(ns user
  (:require
    [clj-reload.core :as reload]))

(reload/init {:dirs ["src/dev" "src/main" "src/test" "src/demo"]})
