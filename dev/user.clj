(ns user
  "REPL entry point. Boot the system with (go), reload changes with (reset),
  stop with (halt)."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as tn]
            [integrant.core :as ig]
            [integrant.repl :as ig-repl]
            [integrant.repl.state :as ig-state]
            flaglog.system))

(tn/set-refresh-dirs "src" "dev" "test")

(defn- read-dev-config []
  (aero/read-config (io/resource "config.edn") {:profile :dev}))

(ig-repl/set-prep! read-dev-config)

(def go    ig-repl/go)
(def halt  ig-repl/halt)
(def reset ig-repl/reset)

(defn system [] ig-state/system)
(defn db     [] (get-in ig-state/system [:flaglog/db :conn]))
