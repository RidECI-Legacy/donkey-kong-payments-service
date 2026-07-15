(ns rideci.payments.main
  (:require
   [rideci.payments.configuration.env]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [rideci.payments.infrastructure.adapters.in.rest-controller :as controller])
  (:import [io.github.cdimascio.dotenv Dotenv])
  (:gen-class))

(try (-> (Dotenv/configure) .load) (catch Exception _))

(defn- parse-json-params [request]
  (if-let [body (:body request)]
    (assoc request :body-params (walk/keywordize-keys body))
      request))

(defn routes
  "Enrutador que conecta los endpoints HTTP con el controlador"
  [request]
  (let [uri (:uri request)
        method (:request-method request)]
    (cond
      ;; 1. Inicializar Pago (POST)
      (and (= uri "/api/payments/initialize") (= method :post))
      (controller/handle-initialize-payment (parse-json-params request))

      ;; 2. Confirmar Efectivo (POST)
      (and (= uri "/api/payments/confirm-cash") (= method :post))
      (controller/handle-confirm-cash (parse-json-params request))

      ;; 3. Webhook de Mercado Pago (POST)
      (and (= uri "/api/payments/webhook") (= method :post))
      (controller/handle-mercado-pago-webhook (parse-json-params request))

      ;; 4. GET Historial por Usuario (Mapea /api/payments/user/:user-id)
      (and (str/starts-with? uri "/api/payments/user/") (= method :get))
      (let [user-id (subs uri (count "/api/payments/user/"))]
        (controller/handle-get-payments-by-user user-id))

      ;; 5. GET Transacción específica por ID (Mapea /api/payments/:id)
      (and (str/starts-with? uri "/api/payments/") (= method :get))
      (let [tx-id (subs uri (count "/api/payments/"))]
        (controller/handle-get-transaction-by-id tx-id))

      :else
      {:status 404 :body {:error "Ruta no encontrada"}})))

(def app (-> routes wrap-json-response wrap-json-body))

(defn -main [& args]
  (let [port 3000]
    (println "🚀 Servidor de pagos RIDECI corriendo en el puerto" port)
    (run-jetty app {:port port :join? false})))