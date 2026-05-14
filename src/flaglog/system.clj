(ns flaglog.system
  "Integrant component wiring. Each defmethod here turns a key from
  resources/config.edn into a live component, and the matching halt-key!
  cleanly tears it down."
  (:require [aero.core :as aero]
            [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [flaglog.db :as db]
            [flaglog.http :as http]))

;; Teach Aero how to read Integrant's #ig/ref tag in config.edn. Lives here
;; (not in flaglog.main) so the REPL workflow and any other config consumer
;; gets the registration just by requiring flaglog.system.
(defmethod aero/reader 'ig/ref
  [_opts _tag value]
  (ig/ref value))

;; ---------- :flaglog/logging ----------

(defmethod ig/init-key :flaglog/logging [_ {:keys [publisher]}]
  (case publisher
    :noop    nil
    :console (mu/start-publisher! {:type :console})))

(defmethod ig/halt-key! :flaglog/logging [_ stop-fn]
  (when stop-fn (stop-fn)))

;; ---------- :flaglog/db ----------

(defmethod ig/init-key :flaglog/db [_ {:keys [datomic-uri]}]
  (db/start! datomic-uri))

(defmethod ig/halt-key! :flaglog/db [_ {:keys [conn uri]}]
  (db/stop! conn uri))

;; ---------- :flaglog/http ----------

(defmethod ig/init-key :flaglog/http [_ {:keys [port host db]}]
  (http/start-server! {:port port :host host :db db}))

(defmethod ig/halt-key! :flaglog/http [_ server]
  (http/stop-server! server))
