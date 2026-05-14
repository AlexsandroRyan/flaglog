(ns flaglog.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [muuntaja.core :as m]
            [flaglog.db :as db]
            [flaglog.http :as http]))

(def ^:dynamic *handler* nil)
(def ^:dynamic *teardown* nil)

(def ^:private muuntaja (m/create))

(defn- encode [body]
  (slurp (m/encode muuntaja "application/json" body)))

(defn- decode [response]
  (let [body (:body response)]
    (cond
      (nil? body) nil
      (string? body) (m/decode muuntaja "application/json" body)
      :else (m/decode muuntaja "application/json" (slurp body)))))

(defn- request
  ([method uri]      (request method uri nil))
  ([method uri body] (request method uri body nil))
  ([method uri body query-string]
   (cond-> {:request-method method
            :uri            uri
            :headers        {"accept" "application/json"}}
     body         (->
                   (assoc-in [:headers "content-type"] "application/json")
                   (assoc :body (java.io.ByteArrayInputStream.
                                 (.getBytes (encode body) "UTF-8"))))
     query-string (assoc :query-string query-string))))

(defn- with-handler [f]
  (let [uri (str "datomic:mem://flaglog-http-test-" (random-uuid))
        {:keys [conn] :as cmp} (db/start! uri)
        h (http/handler cmp)]
    (try
      (binding [*handler* h]
        (f))
      (finally
        (db/stop! conn uri)))))

(use-fixtures :each with-handler)

(deftest healthz-returns-ok
  (let [resp (*handler* (request :get "/healthz"))]
    (is (= 200 (:status resp)))
    (is (= "ok" (:status (decode resp))))))

(deftest put-then-get-roundtrip
  (let [put-resp (*handler* (request :put "/flags/checkout.new-flow"
                                     {:value "false" :actor "alex" :reason "initial"}))]
    (is (= 200 (:status put-resp)))
    (is (= "false" (:value (decode put-resp)))))

  (let [get-resp (*handler* (request :get "/flags/checkout.new-flow"))]
    (is (= 200 (:status get-resp)))
    (is (= "false" (:value (decode get-resp))))))

(deftest get-unknown-flag-404
  (let [resp (*handler* (request :get "/flags/does.not.exist"))]
    (is (= 404 (:status resp)))))

(deftest history-endpoint-returns-timeline
  (*handler* (request :put "/flags/k" {:value "v1" :actor "alex" :reason "first"}))
  (Thread/sleep 5)
  (*handler* (request :put "/flags/k" {:value "v2" :actor "alex" :reason "second"}))
  (let [resp (*handler* (request :get "/flags/k/history"))
        body (decode resp)]
    (is (= 200 (:status resp)))
    (is (= "k" (:key body)))
    (is (= 2 (count (:history body))))
    (is (= ["v1" "v2"] (map :value (:history body))))))

(deftest as-of-query-param
  (*handler* (request :put "/flags/feature.x" {:value "off" :actor "alex" :reason "init"}))
  (Thread/sleep 10)
  (let [mid (str (.toInstant (java.util.Date.)))]
    (Thread/sleep 10)
    (*handler* (request :put "/flags/feature.x" {:value "on" :actor "alex" :reason "launch"}))

    (testing "current returns latest"
      (is (= "on" (:value (decode (*handler* (request :get "/flags/feature.x")))))))

    (testing "as-of returns historical value"
      (let [resp (*handler* (request :get "/flags/feature.x" nil
                                     (str "as-of=" mid)))]
        (is (= 200 (:status resp)))
        (is (= "off" (:value (decode resp))))))))

(deftest invalid-as-of-returns-400
  (*handler* (request :put "/flags/k" {:value "v" :actor "alex" :reason "x"}))
  (let [resp (*handler* (request :get "/flags/k" nil "as-of=not-a-date"))]
    (is (= 400 (:status resp)))
    (is (str/includes? (:error (decode resp)) "invalid as-of"))))

(deftest list-flags
  (*handler* (request :put "/flags/a" {:value "1" :actor "alex" :reason "x"}))
  (*handler* (request :put "/flags/b" {:value "2" :actor "alex" :reason "x"}))
  (let [resp (*handler* (request :get "/flags"))
        flags (:flags (decode resp))]
    (is (= 200 (:status resp)))
    (is (= 2 (count flags)))
    (is (= #{"a" "b"} (set (map :key flags))))))

(deftest set-flag-missing-actor-returns-400
  (let [resp (*handler* (request :put "/flags/k" {:value "v"}))]
    (is (= 400 (:status resp)))
    (is (str/includes? (str/lower-case (:error (decode resp))) "actor"))))

(deftest unknown-route-returns-404
  (let [resp (*handler* (request :get "/nope"))]
    (is (= 404 (:status resp)))))
