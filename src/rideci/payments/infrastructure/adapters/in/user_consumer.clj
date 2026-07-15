(ns rideci.payments.infrastructure.adapters.in.user-consumer
  (:require [cheshire.core :as json]
            [rideci.payments.domain.dtos :as dtos]
            [malli.core :as m]))

(defn start-user-consumer
  "Escucha las actualizaciones de usuarios y mantiene actualizado el caché en Redis."
  [channel cache-service]
  (let [subscribe (requiring-resolve 'langohr.basic/subscribe)]
    (subscribe channel "user.events.queue" {:auto-ack true}
               (fn [_ _ ^bytes payload]
                 (try
                   (let [msg (json/parse-string (String. payload "UTF-8") true)]
                     (if (m/validate dtos/UserDTO msg)
                       ((:set-cache cache-service)
                        (str "user:" (:user_id msg))
                        (json/generate-string msg)
                        9600)
                       (println "Error: Payload de usuario inválido contra UserDTO:" msg)))
                   (catch Exception e
                     (println "Error procesando flujo de sincronización de usuario:" (.getMessage e))))))))