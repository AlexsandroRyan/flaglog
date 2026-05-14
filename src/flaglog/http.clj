(ns flaglog.http
  "Ring/Reitit HTTP layer. The handler is a pure function of (db -> handler), so
  tests can build a handler against an in-memory db without booting Jetty.

  Endpoints:
    GET    /healthz                  liveness probe
    GET    /flags                    list all flags + current values
    PUT    /flags/:key                set or update a flag
    GET    /flags/:key                current value (or as-of=<iso8601>)
    GET    /flags/:key/history        full change timeline"
  (:require [clojure.instant :as instant]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [flaglog.flags :as flags]))

;; ---------- helpers ----------

(defn- json-response [status body] {:status status :body body})

(defn- ->iso [^java.util.Date d]
  (when d (.toInstant d)))

(defn- present-flag [f]
  (when f
    (cond-> f
      (:created-at f) (update :created-at ->iso)
      (:at f)         (update :at ->iso)
      (:as-of f)      (update :as-of ->iso))))

(defn- present-history [hist]
  (map (fn [h] (update h :at ->iso)) hist))

(defn- parse-iso-inst [s]
  (when (and s (not (str/blank? s)))
    (try (instant/read-instant-date s)
         (catch Exception _ ::invalid))))

;; ---------- handlers ----------

(defn- list-flags-handler [{::keys [db]}]
  (fn [_]
    (json-response 200 {:flags (map present-flag (flags/list-flags (:conn db)))})))

(defn- set-flag-handler [{::keys [db]}]
  (fn [{:keys [path-params body-params]}]
    (let [key (or (:key path-params) (get path-params "key"))
          {:keys [value actor reason description]} body-params
          result (flags/set-flag! (:conn db)
                                  {:key key :value value
                                   :actor actor :reason reason
                                   :description description})]
      (mu/log ::flag-set :key key :actor actor)
      (json-response 200 (present-flag result)))))

(defn- get-flag-handler [{::keys [db]}]
  (fn [{:keys [path-params query-params]}]
    (let [key    (or (:key path-params) (get path-params "key"))
          as-of  (get query-params "as-of")
          parsed (parse-iso-inst as-of)]
      (cond
        (= parsed ::invalid)
        (json-response 400 {:error "invalid as-of, expected ISO-8601 instant"})

        parsed
        (if-let [f (flags/flag-as-of (:conn db) key parsed)]
          (json-response 200 (present-flag f))
          (json-response 404 {:error "flag not found at requested time"}))

        :else
        (if-let [f (flags/get-flag (:conn db) key)]
          (json-response 200 (present-flag f))
          (json-response 404 {:error "flag not found"}))))))

(defn- history-handler [{::keys [db]}]
  (fn [{:keys [path-params]}]
    (let [key  (or (:key path-params) (get path-params "key"))
          hist (flags/flag-history (:conn db) key)]
      (if (seq hist)
        (json-response 200 {:key key :history (present-history hist)})
        (json-response 404 {:error "flag not found"})))))

(defn- healthz-handler [_]
  (fn [_] (json-response 200 {:status "ok"})))

;; ---------- error mapping ----------

(defn- ex-handler [e _request]
  (let [{:keys [type field]} (ex-data e)]
    (case type
      :flaglog.flags/invalid
      {:status 400 :body {:error (.getMessage e) :field field}}
      ;; default
      {:status 500 :body {:error "internal error"}})))

(def ^:private exception-middleware
  (exception/create-exception-middleware
   (merge exception/default-handlers
          {clojure.lang.ExceptionInfo ex-handler
           ::exception/default        ex-handler})))

;; ---------- request logger ----------

(defn- wrap-request-log [handler]
  (fn [req]
    (let [start (System/nanoTime)
          {:keys [request-method uri]} req
          resp (handler req)
          dur-ms (/ (- (System/nanoTime) start) 1e6)]
      (mu/log ::request
              :method request-method
              :uri uri
              :status (:status resp)
              :duration-ms dur-ms)
      resp)))

;; ---------- routes ----------

(defn router [db]
  (ring/router
   [["/healthz" {:get (healthz-handler {::db db})}]
    ["/flags"
     ["" {:get (list-flags-handler {::db db})}]
     ["/:key"
      ["" {:get (get-flag-handler {::db db})
           :put (set-flag-handler {::db db})}]
      ["/history" {:get (history-handler {::db db})}]]]]
   {:data {:muuntaja   m/instance
           :middleware [parameters/parameters-middleware
                        muuntaja-mw/format-negotiate-middleware
                        muuntaja-mw/format-response-middleware
                        exception-middleware
                        muuntaja-mw/format-request-middleware
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware]}}))

(defn handler
  "Builds a ring handler. Pass a db component map ({:conn ..} from flaglog.db/start!)."
  [db]
  (-> (ring/ring-handler
       (router db)
       (ring/create-default-handler
        {:not-found (constantly {:status 404 :body {:error "not found"}})}))
      wrap-request-log))

;; ---------- server lifecycle ----------

(defn start-server! [{:keys [port host db]}]
  (mu/log ::http-start :port port :host host)
  (jetty/run-jetty (handler db) {:port port :host host :join? false}))

(defn stop-server! [server]
  (mu/log ::http-stop)
  (when server (.stop server)))
