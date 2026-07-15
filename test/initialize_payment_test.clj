(ns initialize-payment-test
  (:require [clojure.test :refer [deftest is testing]]
            [rideci.payments.application.use-cases.initialize-payment :as sut]))

(defn- id-ports []
  {::saved-tx       (atom nil)
   ::updated-tx     (atom nil)
   ::notifications (atom [])
   ::cache        (atom {})
   ::find-mock    (atom nil)
   ::find-user-mock (atom nil)})

(defn- mock-repo [ports]
  {:save! (fn [tx] (reset! (::saved-tx ports) tx) tx)
   :update-status-atomic! (fn [id status version]
                            (reset! (::updated-tx ports) {:id id :status status :version version})
                            (if (= id "tx-conflict") 0 1))
   :find-by-id (fn [id]
                 (if (= id "tx-explode")
                   (throw (RuntimeException. "DB Crash"))
                   @(::find-mock ports)))
   :find-by-user-id (fn [user-id]
                      (if (= user-id "user-explode")
                        (throw (RuntimeException. "DB Error"))
                        @(::find-user-mock ports)))})

(defn- mock-cache [ports]
  {:save-if-not-exists! (fn [key val ttl]
                          (if (get @(::cache ports) key)
                            false
                            (do (swap! (::cache ports) assoc key val) true)))
   :delete! (fn [key] (swap! (::cache ports) dissoc key))})

(defn- mock-gateway [comportamiento preference-id]
  (fn [tx-data]
    (if (= comportamiento :api-caida)
      {:id nil :status "failed" :init-point nil}
      {:id preference-id :status comportamiento :init-point "https://mp.checkout.url"})))

(defn- mock-notifier [ports]
  {:notify! (fn [event-map] (swap! (::notifications ports) conj event-map))})

(deftest flujo-pago-efectivo-exitoso-test
  (testing "Caso 1 - Flujo de pago en efectivo exitoso"
    (let [ports    (id-ports)
          repo     (mock-repo ports)
          cache    (mock-cache ports)
          gateway  (mock-gateway "pending" "pref-123")
          notifier (mock-notifier ports)
          payload  {:travel {:travel_id "viaje-10" :trip_name "ECI Bloque G" :estimatedCost 8000.0 :payment_method "cash"}
                    :user {:user_id 777 :email "juan@escuelaing.edu.co" :phone "555" :role "PASSENGER"}}
          result   (sut/execute repo cache gateway notifier payload)]
      (is (= "success" (:status result)))
      (is (= "pending_cash_verification" (:gateway-status result)))
      (is (= "pending_cash_verification" (:status @(::saved-tx ports))))
      (is (= "payment.cash_requested" (:event (first @(::notifications ports))))))))

(deftest flujo-pago-tarjeta-exitoso-test
  (testing "Caso 2 - Flujo de pago con tarjeta exitoso"
    (let [ports    (id-ports)
          repo     (mock-repo ports)
          cache    (mock-cache ports)
          gateway  (mock-gateway "approved" "mp-sec-999")
          notifier (mock-notifier ports)
          payload  {:travel {:travel_id "viaje-20" :trip_name "ECI Laboratorios" :estimatedCost 12000.0 :payment_method "card"}
                    :user {:user_id 888 :email "juan@escuelaing.edu.co" :phone "444" :role "PASSENGER"}}
          result   (sut/execute repo cache gateway notifier payload)]
      (is (= "success" (:status result)))
      (is (= "approved" (:gateway-status result)))
      (is (= "https://mp.checkout.url" (:checkout-url result)))
      (is (= "approved" (:status @(::updated-tx ports)))))))

(deftest validacion-esquema-invalido-test
  (testing "Caso 3 - Retorno controlado de Malli ante un esquema inválido"
    (let [ports    (id-ports)
          repo     (mock-repo ports)
          cache    (mock-cache ports)
          gateway  (mock-gateway "pending" "id")
          notifier (mock-notifier ports)
          payload  {:travel {:trip_name "Intento Fallido"}
                    :user {:user_id 999 :email "juan@escuelaing.edu.co" :role "PASSENGER"}}]
      (is (thrown-with-msg? Exception #"Estructura de transacción inválida"
                            (sut/execute repo cache gateway notifier payload))))))

(deftest control-idempotencia-duplicados-test
  (testing "Caso 4 - Bloqueo de peticiones duplicadas usando el caché de Redis"
    (let [ports    (id-ports)
          repo     (mock-repo ports)
          cache    (mock-cache ports)
          gateway  (mock-gateway "pending" "id")
          notifier (mock-notifier ports)
          payload  {:travel {:travel_id "viaje-repetido" :trip_name "Doble Click" :estimatedCost 5000.0 :payment_method "cash"}
                    :user {:user_id 111 :email "juan@escuelaing.edu.co" :phone "111" :role "PASSENGER"}}]
      (swap! (::cache ports) assoc "pay-viaje-repetido" "processing")
      (is (thrown-with-msg? Exception #"Transacción ya se encuentra duplicada"
                            (sut/execute repo cache gateway notifier payload))))))

(deftest caida-del-gateway-catch-test
  (testing "Caso 5 - Manejo de excepciones y rollback cuando la API externa falla"
    (let [ports    (id-ports)
          repo     (mock-repo ports)
          cache    (mock-cache ports)
          gateway  (mock-gateway :api-caida nil)
          notifier (mock-notifier ports)
          payload  {:travel {:travel_id "viaje-error" :trip_name "Error API" :estimatedCost 9000.0 :payment_method "card"}
                    :user {:user_id 222 :email "juan@escuelaing.edu.co" :phone "222" :role "PASSENGER"}}]
      (is (thrown-with-msg? Exception #"El gateway de pagos rechazó la solicitud"
                            (sut/execute repo cache gateway notifier payload)))
      (is (empty? @(::cache ports)))
      (is (= "failed" (:status @(::updated-tx ports))))
      (is (= "payment.failed" (:event (last @(::notifications ports))))))))

(deftest confirm-cash-payment-test
  (testing "Confirmacion exitosa"
    (let [ports (id-ports)
          repo (mock-repo ports)
          notifier (mock-notifier ports)]
      (reset! (::find-mock ports) {:version 1 :travel-id "t1" :trip-name "G" :user-email "j@m.com" :user-phone "3" :amount 5000.0 :currency "COP"})
      (let [res (sut/confirm-cash-payment repo notifier "tx-123")]
        (is (= "completed" (:status res)))
        (is (= "payment.success" (:event (first @(::notifications ports))))))))

  (testing "Confirmacion exitosa con llaves de nombrespace de BD"
    (let [ports (id-ports)
          repo (mock-repo ports)
          notifier (mock-notifier ports)]
      (reset! (::find-mock ports) {:transactions/version 2 :transactions/travel_id "t2" :transactions/trip_name "H" :transactions/user_email "a@m.com" :transactions/user_phone "4" :transactions/amount 6000.0 :transactions/currency "COP"})
      (let [res (sut/confirm-cash-payment repo notifier "tx-123")]
        (is (= "completed" (:status res))))))

  (testing "Conflicto de concurrencia optimista"
    (let [ports (id-ports)
          repo (mock-repo ports)
          notifier (mock-notifier ports)]
      (reset! (::find-mock ports) {:version 1 :travel-id "t1"})
      (is (thrown-with-msg? Exception #"Conflict"
                            (sut/confirm-cash-payment repo notifier "tx-conflict")))))

  (testing "Transaccion no encontrada"
    (let [ports (id-ports)
          repo (mock-repo ports)
          notifier (mock-notifier ports)]
      (reset! (::find-mock ports) nil)
      (is (thrown-with-msg? Exception #"Transacción no encontrada"
                            (sut/confirm-cash-payment repo notifier "tx-missing")))))

  (testing "Excepcion general"
    (let [ports (id-ports)
          repo (mock-repo ports)
          notifier (mock-notifier ports)]
      (is (thrown? Exception (sut/confirm-cash-payment repo notifier "tx-explode"))))))

(deftest get-transaction-by-id-test
  (testing "Encontrada"
    (let [ports (id-ports)
          repo (mock-repo ports)]
      (reset! (::find-mock ports) {:id "tx-1"})
      (is (= {:id "tx-1"} (sut/get-transaction-by-id repo "tx-1")))))

  (testing "No encontrada"
    (let [ports (id-ports)
          repo (mock-repo ports)]
      (reset! (::find-mock ports) nil)
      (is (thrown-with-msg? Exception #"Transacción no encontrada"
                            (sut/get-transaction-by-id repo "tx-2")))))

  (testing "Excepcion general"
    (let [ports (id-ports)
          repo (mock-repo ports)]
      (is (thrown? Exception (sut/get-transaction-by-id repo "tx-explode"))))))

(deftest get-payments-by-user-test
  (testing "Historial exitoso"
    (let [ports (id-ports)
          repo (mock-repo ports)]
      (reset! (::find-user-mock ports) [{:id "tx-1"}])
      (is (= [{:id "tx-1"}] (sut/get-payments-by-user repo 111)))))

  (testing "Excepcion general"
    (let [ports (id-ports)
          repo (mock-repo ports)]
      (is (thrown? Exception (sut/get-payments-by-user repo "user-explode"))))))