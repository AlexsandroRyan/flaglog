(ns flaglog.main
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [flaglog.system])
  (:gen-class))

(defn- read-config
  ([] (read-config :prod))
  ([profile]
   (aero/read-config (io/resource "config.edn") {:profile profile})))

(defonce ^:private system (atom nil))

(defn- shutdown! []
  (when-let [sys @system]
    (mu/log ::shutdown :phase :start)
    (ig/halt! sys)
    (reset! system nil)
    (mu/log ::shutdown :phase :done)))

(defn -main [& _args]
  (let [cfg (read-config :prod)
        sys (ig/init cfg)]
    (reset! system sys)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable shutdown!))
    (mu/log ::started :port (get-in cfg [:flaglog/http :port]))
    @(promise))) ;; block forever
