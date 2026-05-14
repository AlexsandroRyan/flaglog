(ns flaglog.flags
  "Core domain operations for flags.

  The shape of this namespace is the heart of the project:

  - `set-flag!` upserts a flag value. Each call is recorded as its own
    transaction with reified `:tx/actor` and `:tx/reason` metadata, so the
    audit log answers 'who changed this and why', not just 'what did it change to'.

  - `get-flag` reads the current value from the live db.

  - `flag-history` walks (d/history db) to produce an ordered timeline of every
    set. This is the canonical Datomic pattern — no audit table needed.

  - `flag-as-of` answers 'what was the value at point in time T' using
    (d/as-of db t). Pure Datomic — also no extra table.

  - `list-flags` returns every flag with its current value."
  (:require [clojure.string :as str]
            [datomic.api :as d]))

(defn- now-inst [] (java.util.Date.))

(defn- blank? [s] (or (nil? s) (and (string? s) (str/blank? s))))

(defn- normalize-key [k]
  (when-not (blank? k)
    (str/trim k)))

(defn set-flag!
  "Set or update a flag. `value` is stored verbatim — callers JSON-encode upstream.
  `actor` and `reason` are recorded on the transaction so the audit log keeps them.

  Returns {:key k :value v :tx-id <eid> :at <inst>}."
  [conn {:keys [key value actor reason description]}]
  (let [k (normalize-key key)]
    (when (blank? k)      (throw (ex-info "flag key is required" {:type ::invalid :field :key})))
    (when (nil? value)    (throw (ex-info "flag value is required" {:type ::invalid :field :value})))
    (when (blank? actor)  (throw (ex-info "actor is required for auditability" {:type ::invalid :field :actor})))
    (let [existing?   (some? (d/q '[:find ?e .
                                    :in $ ?k
                                    :where [?e :flag/key ?k]]
                                  (d/db conn) k))
          flag-entity (cond-> {:db/id      "flaglog/flag"
                               :flag/key   k
                               :flag/value value}
                        (not existing?)            (assoc :flag/created-at (now-inst))
                        (not (blank? description)) (assoc :flag/description description))
          tx-data     (cond-> [flag-entity
                               [:db/add "datomic.tx" :tx/actor actor]]
                        (not (blank? reason)) (conj [:db/add "datomic.tx" :tx/reason reason]))
          {:keys [tempids db-after]} @(d/transact conn tx-data)
          tx-id       (get tempids "datomic.tx")
          tx-inst     (when tx-id (:db/txInstant (d/entity db-after tx-id)))]
      {:key   k
       :value value
       :tx-id tx-id
       :at    tx-inst})))

(defn get-flag
  "Returns the current state of a flag, or nil if it has never been set."
  [conn key]
  (let [k (normalize-key key)
        db (d/db conn)
        e  (d/entity db [:flag/key k])]
    (when e
      {:key         (:flag/key e)
       :value       (:flag/value e)
       :description (:flag/description e)
       :created-at  (:flag/created-at e)})))

(defn flag-history
  "Returns an ordered timeline of every value the flag has held.
  Each entry: {:value v :at inst :actor a :reason r}.
  Ordered oldest -> newest."
  [conn key]
  (let [k       (normalize-key key)
        db      (d/db conn)
        history (d/history db)]
    (->> (d/q '[:find ?v ?tx ?inst ?added
                :in $ ?k
                :where
                [?e :flag/key ?k]
                [?e :flag/value ?v ?tx ?added]
                [?tx :db/txInstant ?inst]]
              history k)
         (filter (fn [[_ _ _ added]] added))
         (sort-by #(nth % 2))
         (map (fn [[v tx inst _]]
                (let [tx-ent (d/entity db tx)]
                  {:value  v
                   :at     inst
                   :actor  (:tx/actor tx-ent)
                   :reason (:tx/reason tx-ent)}))))))

(defn flag-as-of
  "Returns the value of a flag as it stood at `inst` (a java.util.Date), or nil."
  [conn key inst]
  (let [k       (normalize-key key)
        as-of-db (d/as-of (d/db conn) inst)
        e        (d/entity as-of-db [:flag/key k])]
    (when e
      {:key   (:flag/key e)
       :value (:flag/value e)
       :as-of inst})))

(defn list-flags
  "Returns all flags with their current values, sorted by key."
  [conn]
  (->> (d/q '[:find ?k ?v ?desc ?created
              :where
              [?e :flag/key ?k]
              [?e :flag/value ?v]
              [(get-else $ ?e :flag/description "") ?desc]
              [(get-else $ ?e :flag/created-at #inst "1970-01-01") ?created]]
            (d/db conn))
       (map (fn [[k v desc created]]
              (cond-> {:key k :value v}
                (not (str/blank? desc)) (assoc :description desc)
                (not= (.getTime ^java.util.Date created) 0) (assoc :created-at created))))
       (sort-by :key)))
