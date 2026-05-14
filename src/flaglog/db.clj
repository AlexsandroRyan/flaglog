(ns flaglog.db
  "Datomic lifecycle + schema bootstrap.

  This namespace is intentionally thin: it knows how to create/connect to a
  database and install the schema. Domain operations live in flaglog.flags."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]
            [datomic.api :as d]))

(defn- load-schema []
  (-> "schema.edn" io/resource slurp edn/read-string))

(defn- install-schema! [conn]
  @(d/transact conn (load-schema)))

(defn start!
  "Ensures the database exists, connects to it, and installs the schema.
  Returns a map {:conn ... :uri ...} that becomes the component value."
  [uri]
  (mu/log ::db-start :uri uri)
  (d/create-database uri)
  (let [conn (d/connect uri)]
    (install-schema! conn)
    {:conn conn :uri uri}))

(defn stop!
  "Releases the connection. In-memory dev databases are also deleted so
  repeated REPL boots get a clean slate."
  [conn uri]
  (mu/log ::db-stop :uri uri)
  (when conn (d/release conn))
  (when (and uri (re-find #"^datomic:mem://" uri))
    (d/delete-database uri)))
