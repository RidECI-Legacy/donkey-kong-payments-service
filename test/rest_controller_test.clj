(ns rest-controller-test
  (:require [clojure.test :refer [deftest is testing]]
            [rideci.payments.application.use-cases.initialize-payment :as init-payment]
            [rideci.payments.infrastructure.adapters.out.postgres-repo :as repo]
            [rideci.payments.configuration.rabbitmq :as rabbit-config]))

(with-redefs [repo/create-datasource (fn [] nil)
              repo/->PostgresRepository (fn [_] nil)
              rabbit-config/connect-and-open-channel (fn [] {:conn nil :ch nil})]
  (require '[rideci.payments.infrastructure.adapters.in.rest-controller :as sut]))

(deftest handle-initialize-payment-test
  (testing "Inicializacion exitosa (201)"
    (with-redefs [init-payment/execute (fn [_ _ _ _ payload] {:status "success" :payload payload})]
      (let [req {:body-params {:travel {:travel_id "t-1"} :user {:user_id "u-1"}}}
            res (sut/handle-initialize-payment req)]
        (is (= 201 (:status res)))
        (is (= "success" (get-in res [:body :status]))))))

  (testing "Inicializacion fallida por excepcion (400)"
    (with-redefs [init-payment/execute (fn [_ _ _ _ _] (throw (Exception. "Invalid logic")))]
      (let [req {:body-params {:travel nil}}
            res (sut/handle-initialize-payment req)]
        (is (= 400 (:status res)))
        (is (= "Invalid logic" (get-in res [:body :error])))))))

(deftest handle-confirm-cash-test
  (testing "Confirmacion exitosa (200)"
    (with-redefs [init-payment/confirm-cash-payment (fn [_ _ tx-id] {:status "completed" :id tx-id})]
      (let [req {:body-params {:transaction_id "tx-100"}}
            res (sut/handle-confirm-cash req)]
        (is (= 200 (:status res)))
        (is (= "completed" (get-in res [:body :status]))))))

  (testing "Error controlado por ex-data (409/404)"
    (with-redefs [init-payment/confirm-cash-payment (fn [_ _ _] (throw (ex-info "Optimistic lock" {:status 409})))]
      (let [req {:body-params {:transaction_id "tx-100"}}
            res (sut/handle-confirm-cash req)]
        (is (= 409 (:status res))))))

  (testing "Error general sin status en ex-data (400)"
    (with-redefs [init-payment/confirm-cash-payment (fn [_ _ _] (throw (Exception. "Fatal runtime error")))]
      (let [req {:body-params {:transaction_id "tx-100"}}
            res (sut/handle-confirm-cash req)]
        (is (= 400 (:status res)))))))

(deftest handle-get-transaction-by-id-test
  (testing "Transaccion encontrada (200)"
    (with-redefs [init-payment/get-transaction-by-id (fn [_ tx-id] {:id tx-id :amount 100})]
      (let [res (sut/handle-get-transaction-by-id "tx-777")]
        (is (= 200 (:status res)))
        (is (= "tx-777" (get-in res [:body :id]))))))

  (testing "Transaccion no encontrada (404)"
    (with-redefs [init-payment/get-transaction-by-id (fn [_ _] (throw (ex-info "Not found" {:status 404})))]
      (let [res (sut/handle-get-transaction-by-id "tx-missing")]
        (is (= 404 (:status res))))))

  (testing "Fallo interno del servidor (500)"
    (with-redefs [init-payment/get-transaction-by-id (fn [_ _] (throw (Exception. "DB Timeout")))]
      (let [res (sut/handle-get-transaction-by-id "tx-error")]
        (is (= 500 (:status res)))))))

(deftest handle-get-payments-by-user-test
  (testing "Historial de usuario obtenido (200)"
    (with-redefs [init-payment/get-payments-by-user (fn [_ user-id] [{:id "1" :user user-id}])]
      (let [res (sut/handle-get-payments-by-user "user-555")]
        (is (= 200 (:status res)))
        (is (= "user-555" (get-in res [:body :user_id])))
        (is (= 1 (count (get-in res [:body :transactions])))))))

  (testing "Historial vacio si retorna nulo (200)"
    (with-redefs [init-payment/get-payments-by-user (fn [_ _] nil)]
      (let [res (sut/handle-get-payments-by-user "user-empty")]
        (is (= 200 (:status res)))
        (is (= [] (get-in res [:body :transactions]))))))

  (testing "Error interno obteniendo historial (500)"
    (with-redefs [init-payment/get-payments-by-user (fn [_ _] (throw (Exception. "Broken connection")))]
      (let [res (sut/handle-get-payments-by-user "user-fail")]
        (is (= 500 (:status res)))))))

(deftest handle-mercado-pago-webhook-test
  (testing "Webhook procesado con data.id estructurado (200)"
    (let [req {:body-params {:action "payment.created" :data {:id "mp-999"}}}
          res (sut/handle-mercado-pago-webhook req)]
      (is (= 200 (:status res)))
      (is (= true (get-in res [:body :received])))))

  (testing "Webhook procesado con id directo (200)"
    (let [req {:body-params {:action "payment.updated" :id "mp-888"}}
          res (sut/handle-mercado-pago-webhook req)]
      (is (= 200 (:status res)))))

  (testing "Webhook lanza excepcion (400)"
    (with-redefs [clojure.core/get-in (fn [& _] (throw (Exception. "Forced error")))]
      (let [req {:body-params {:data {:id "mp-999"}}}
            res (sut/handle-mercado-pago-webhook req)]
        (is (= 400 (:status res)))))))