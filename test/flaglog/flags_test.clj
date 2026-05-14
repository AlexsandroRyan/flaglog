(ns flaglog.flags-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [flaglog.db :as db]
            [flaglog.flags :as flags]))

(def ^:dynamic *conn* nil)

(defn- with-fresh-db [f]
  (let [uri (str "datomic:mem://flaglog-test-" (random-uuid))
        {:keys [conn]} (db/start! uri)]
    (try
      (binding [*conn* conn] (f))
      (finally
        (db/stop! conn uri)))))

(use-fixtures :each with-fresh-db)

(deftest set-and-get-roundtrip
  (testing "setting a flag makes it readable"
    (flags/set-flag! *conn* {:key "checkout.new-flow" :value "false"
                             :actor "alex" :reason "initial"})
    (let [got (flags/get-flag *conn* "checkout.new-flow")]
      (is (= "checkout.new-flow" (:key got)))
      (is (= "false" (:value got)))
      (is (inst? (:created-at got))))))

(deftest update-overwrites-current-value
  (flags/set-flag! *conn* {:key "k" :value "v1" :actor "alex" :reason "first"})
  (flags/set-flag! *conn* {:key "k" :value "v2" :actor "alex" :reason "second"})
  (is (= "v2" (:value (flags/get-flag *conn* "k")))))

(deftest history-records-every-change-with-actor-and-reason
  (flags/set-flag! *conn* {:key "rollout.percent" :value "10"
                           :actor "alex" :reason "soft launch"})
  (Thread/sleep 5) ;; ensure distinct tx instants
  (flags/set-flag! *conn* {:key "rollout.percent" :value "50"
                           :actor "alex" :reason "scaling up"})
  (Thread/sleep 5)
  (flags/set-flag! *conn* {:key "rollout.percent" :value "100"
                           :actor "ops-bot" :reason "full rollout"})

  (let [hist (flags/flag-history *conn* "rollout.percent")]
    (is (= 3 (count hist)))
    (testing "ordered oldest -> newest"
      (is (= ["10" "50" "100"] (map :value hist))))
    (testing "actor and reason preserved per entry"
      (is (= ["alex" "alex" "ops-bot"] (map :actor hist)))
      (is (= ["soft launch" "scaling up" "full rollout"] (map :reason hist))))
    (testing "every entry has a timestamp"
      (is (every? inst? (map :at hist))))))

(deftest as-of-returns-value-at-point-in-time
  (flags/set-flag! *conn* {:key "feature.x" :value "off" :actor "alex" :reason "init"})
  (Thread/sleep 10)
  (let [mid (java.util.Date.)]
    (Thread/sleep 10)
    (flags/set-flag! *conn* {:key "feature.x" :value "on" :actor "alex" :reason "launch"})

    (testing "as-of mid-point sees the original value"
      (is (= "off" (:value (flags/flag-as-of *conn* "feature.x" mid)))))
    (testing "current still reflects the latest value"
      (is (= "on" (:value (flags/get-flag *conn* "feature.x")))))))

(deftest list-flags-returns-all-with-current-values
  (flags/set-flag! *conn* {:key "a" :value "1" :actor "alex" :reason "x"})
  (flags/set-flag! *conn* {:key "b" :value "2" :actor "alex" :reason "x" :description "the B flag"})
  (let [all (flags/list-flags *conn*)]
    (is (= 2 (count all)))
    (is (= ["a" "b"] (map :key all)))
    (is (= "the B flag" (:description (second all))))))

(deftest validation-rejects-bad-input
  (testing "missing key"
    (is (thrown? clojure.lang.ExceptionInfo
                 (flags/set-flag! *conn* {:value "v" :actor "alex"}))))
  (testing "blank key"
    (is (thrown? clojure.lang.ExceptionInfo
                 (flags/set-flag! *conn* {:key "   " :value "v" :actor "alex"}))))
  (testing "missing value"
    (is (thrown? clojure.lang.ExceptionInfo
                 (flags/set-flag! *conn* {:key "k" :actor "alex"}))))
  (testing "missing actor (audit requirement)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (flags/set-flag! *conn* {:key "k" :value "v"})))))

(deftest get-unknown-flag-returns-nil
  (is (nil? (flags/get-flag *conn* "does.not.exist"))))

(deftest history-of-unknown-flag-is-empty
  (is (empty? (flags/flag-history *conn* "does.not.exist"))))
