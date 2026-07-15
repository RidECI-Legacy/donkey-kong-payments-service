(ns rideci.payments.infrastructure.adapters.in.rest-controller
  (:require [rideci.payments.application.use-cases.initialize-payment :as init-payment]
            [rideci.payments.infrastructure.adapters.out.mercado-pago-adapter :as mp-adapter]
            [rideci.payments.infrastructure.adapters.out.postgres-repo :as repo]
            [rideci.payments.infrastructure.adapters.out.redis-cache :as redis]
            [rideci.payments.infrastructure.adapters.out.rabbitmq-publisher :as rabbit]
            [rideci.payments.configuration.rabbitmq :as rabbit-config]
            [clojure.tools.logging :as log]))

(def db-source (repo/create-datasource))
(def postgres-repo (repo/->PostgresRepository db-source))
(def rabbit-connection (rabbit-config/connect-and-open-channel))
(def rmq-conn (:conn rabbit-connection))
(def rmq-channel (:ch rabbit-connection))

(def payment-repo-port
  {:save!                 (fn [tx] (repo/save! postgres-repo tx))
   :update-status-atomic! (fn [id status version]
                            (repo/update-status-atomic! postgres-repo id status version))
   :find-by-id            (fn [id] (repo/find-by-id postgres-repo id))
   :find-by-user-id       (fn [user-id] (repo/find-by-user-id postgres-repo user-id))})

(def cache-port
  {:save-if-not-exists! (fn [key val ttl]
                          (redis/save-idempotency! key val ttl))
   :delete!             (fn [key]
                          (redis/delete-idempotency! key))})

(def mp-gateway-port
  mp-adapter/authorize-payment)

(def notifier-port
  (rabbit/create-notifier rmq-channel))

(defn handle-initialize-payment
  "POST /api/payments/initialize"
  [request]
  (try
    (let [body (:body-params request)
          payload {:travel (:travel body)
                   :user (:user body)}
          result (init-payment/execute
                  payment-repo-port
                  cache-port
                  mp-gateway-port
                  notifier-port
                  payload)]
      {:status 201
       :body result})
    (catch Exception e
      (log/error "Error al inicializar pago:" (.getMessage e))
      {:status 400
       :body {:error (.getMessage e)}})))

(defn handle-confirm-cash
  "POST /api/payments/confirm-cash"
  [request]
  (try
    (let [body (:body-params request)
          tx-id (:transaction_id body)
          result (init-payment/confirm-cash-payment
                  payment-repo-port
                  notifier-port
                  tx-id)]
      {:status 200
       :body result})
    (catch Exception e
      (let [data (ex-data e)
            status (or (:status data) 400)]
        {:status status
         :body {:error (.getMessage e)}}))))

(defn handle-get-transaction-by-id
  "GET /api/payments/:id"
  [tx-id]
  (try
    (let [tx (init-payment/get-transaction-by-id payment-repo-port tx-id)]
      {:status 200
       :body tx})
    (catch Exception e
      (let [data (ex-data e)]
        (if (= (:status data) 404)
          {:status 404
           :body {:error (.getMessage e)}}
          {:status 500
           :body {:error (.getMessage e)}})))))

(defn handle-get-payments-by-user
  "GET /api/payments/user/:user-id"
  [user-id]
  (try
    (let [txs (init-payment/get-payments-by-user payment-repo-port user-id)]
      {:status 200
       :body {:user_id user-id
              :transactions (or txs [])}})
    (catch Exception e
      (log/error "Error obteniendo transacciones del usuario:" user-id)
      {:status 500
       :body {:error (.getMessage e)}})))

(defn handle-mercado-pago-webhook
  "POST /api/payments/webhook"
  [request]
  (try
    (let [body (:body-params request)
          mp-payment-id (or (get-in body [:data :id])
                            (:id body))
          action (:action body)]
      (log/info "Webhook de Mercado Pago recibido. Acción:" action "ID Pago MP:" mp-payment-id)
      {:status 200
       :body {:received true}})
    (catch Exception e
      {:status 400
       :body {:error (.getMessage e)}})))