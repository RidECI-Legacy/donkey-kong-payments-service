(ns rideci.payments.application.use-cases.initialize-payment
  (:require [clojure.tools.logging :as log]
            [malli.core :as m]
            [rideci.payments.domain.schemas :as schemas]))

(defn execute
  "Orquesta el proceso de inicialización de pagos soportando efectivo y pasarela digital."
  [repo cache gateway notifier data]
  (let [travel (:travel data)
        user   (:user data)
        method (or (:payment_method travel) "card")

        tx-data {:id                (str (java.util.UUID/randomUUID))
                 :travel-id         (:travel_id travel)
                 :trip-name         (:trip_name travel)
                 :amount            (:estimatedCost travel)
                 :currency          "COP"
                 :status            (if (= method "cash") "pending_cash_verification" "pending")
                 :payment-method    method
                 :version           1
                 :idempotency-key   (str "pay-" (:travel_id travel))
                 :user-id           (:user_id user)
                 :user-email        (:email user)
                 :user-phone        (:phone user)
                 :user-role         (:role user)
                 :created-at        (str (java.time.Instant/now))}]

    (log/info "Iniciando proceso de pago. Modo:" method "para viaje:" (:travel-id tx-data))

    (when-not (m/validate schemas/Transaction tx-data)
      (throw (ex-info "Estructura de transacción inválida contra schemas/Transaction"
                      {:errors (m/explain schemas/Transaction tx-data)})))

    ((:notify! notifier) {:event      (if (= method "cash") "payment.cash_requested" "payment.started")
                          :travel-id  (:travel-id tx-data)
                          :trip-name  (:trip-name tx-data)
                          :user-email (:user-email tx-data)
                          :user-phone (:user-phone tx-data)
                          :amount     (double (:amount tx-data))
                          :currency   (:currency tx-data)
                          :status     (:status tx-data)
                          :timestamp  (:created-at tx-data)})

    (when-not ((:save-if-not-exists! cache) (:idempotency-key tx-data) "processing" 300)
      (throw (ex-info "Transacción ya se encuentra duplicada o en proceso activo" {:key (:idempotency-key tx-data)})))

    (try
      ((:save! repo) tx-data)
      (if (= method "cash")
        {:status "success"
         :transaction-id (:id tx-data)
         :gateway-status "pending_cash_verification"
         :message "Registro de efectivo creado. Esperando verificación del conductor u organizador."}

        (let [gateway-result (gateway tx-data)
              gateway-id     (:id gateway-result)
              gateway-status (:status gateway-result)]

          (if gateway-id
            (do
              ((:update-status-atomic! repo) (:id tx-data) gateway-status 1)

              (merge {:status "success"
                      :transaction-id (:id tx-data)
                      :gateway-status gateway-status}
                     (when (:init-point gateway-result) {:checkout-url (:init-point gateway-result)})))
            (throw (ex-info "El gateway de pagos rechazó la solicitud de inicialización" {:result gateway-result})))))

      (catch Exception e
        (log/error "Fallo en flujo interno de pago:" (.getMessage e))
        ((:delete! cache) (:idempotency-key tx-data))
        ((:update-status-atomic! repo) (:id tx-data) "failed" 1)

        ((:notify! notifier) {:event      "payment.failed"
                              :travel-id  (:travel-id tx-data)
                              :trip-name  (:trip-name tx-data)
                              :user-email (:user-email tx-data)
                              :user-phone (:user-phone tx-data)
                              :amount     (double (:amount tx-data))
                              :currency   (:currency tx-data)
                              :status     "failed"
                              :timestamp  (str (java.time.Instant/now))})
        (throw e)))))

(defn confirm-cash-payment
  "Invocado cuando el conductor confirma manualmente haber recibido el dinero físico."
  [repo notifier tx-id]
  (try 
    (if-let [tx ((:find-by-id repo) tx-id)]
      (let [current-version (or (:version tx) (:transactions/version tx) 1) 
            travel-id       (or (:travel-id tx) (:transactions/travel_id tx))
            trip-name       (or (:trip-name tx) (:transactions/trip_name tx))
            user-email      (or (:user-email tx) (:transactions/user_email tx))
            user-phone      (or (:user-phone tx) (:transactions/user_phone tx))
            amount          (or (:amount tx) (:transactions/amount tx) 0.0)
            currency        (or (:currency tx) (:transactions/currency tx) "COP")
            
            updated-rows    ((:update-status-atomic! repo) tx-id "completed" current-version)]

        (if (and updated-rows (pos? updated-rows))
          (do 
            ((:notify! notifier) {:event      "payment.success"
                                  :travel-id  (str travel-id)
                                  :trip-name  (str trip-name)
                                  :user-email (str user-email)
                                  :user-phone (str user-phone)
                                  :amount     (double amount)
                                  :currency   (str currency)
                                  :status     "completed"
                                  :timestamp  (str (java.time.Instant/now))})
            {:status "completed" :message "Pago manual en efectivo confirmado satisfactoriamente"})
          (throw (ex-info "Conflict: La transacción cambió de estado simultáneamente" {:status 409}))))
      (throw (ex-info "Transacción no encontrada" {:id tx-id :status 404})))
    (catch Exception e
      (log/error "Error procesando confirmación manual de efectivo:" (.getMessage e))
      (throw e))))

(defn get-transaction-by-id
  "Busca y retorna los detalles de una transacción específica."
  [repo tx-id]
  (try
    (if-let [tx ((:find-by-id repo) tx-id)]
      tx
      (throw (ex-info "Transacción no encontrada" {:id tx-id :status 404})))
    (catch Exception e
      (log/error "Error al buscar transacción" tx-id ":" (.getMessage e))
      (throw e))))

(defn get-payments-by-user
  "Trae el historial completo de transacciones para un usuario específico."
  [repo user-id]
  (try
    ((:find-by-user-id repo) user-id)
    (catch Exception e
      (log/error "Error al obtener historial del usuario" user-id ":" (.getMessage e))
      (throw e))))