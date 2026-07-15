(ns rideci.payments.infrastructure.adapters.out.mercado-pago-adapter
  (:require [clj-http.client :as client])
  (:import [io.github.cdimascio.dotenv Dotenv]))

(def ^:private env (-> (Dotenv/configure) .load))

(defn authorize-payment
  "Interactúa con Mercado Pago mediante Checkout Preferences o gestiona el flujo en efectivo."
  [tx-data]
  (let [token (.get env "MP_ACCESS_TOKEN")
        payment-method (:payment-method tx-data)]

    (if (= payment-method "cash") 
      {:id (str "cash-pref-" (java.util.UUID/randomUUID))
       :status "pending_cash_verification"
       :init-point nil}
      
      (try
        (let [body {:external_reference (str (:id tx-data))
                    :items [{:title (str "Viaje RIDECI: " (:trip-name tx-data))
                             :quantity 1
                             :unit_price (:amount tx-data)}]
                    :notification_url "https://api.rideci.com/webhooks/mercadopago"}

              response (client/post "https://api.mercadopago.com/checkout/preferences"
                                    {:headers {"Authorization" (str "Bearer " token)}
                                     :content-type :json
                                     :form-params body
                                     :throw-exceptions false
                                     :as :json})]

          (if (= 201 (:status response))
            (let [resp-body (:body response)] 
              {:id (:id resp-body)
               :status "pending" 
               :init-point (:init_point resp-body)})
            (do
              (println "DEBUG ERROR MERCADO PAGO API:" (:body response))
              {:id nil :status "failed" :init-point nil})))
        (catch Exception e
          (println "Error crítico comunicándose con el Gateway:" (.getMessage e))
          {:id nil :status "failed" :init-point nil})))))