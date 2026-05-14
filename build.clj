(ns build
  "Build the flaglog uberjar with tools.build.

  Usage:
    clojure -T:build clean
    clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/flaglog.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'flaglog.main}))
