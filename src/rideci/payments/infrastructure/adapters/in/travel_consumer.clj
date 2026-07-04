(ns rideci.payments.infrastructure.adapters.in.travel-consumer
  (:require [rideci.payments.domain.dtos :as dtos]
            [malli.core :as m]
            [cheshire.core :as json]))

(defn start-consumer
  "Inicia la escucha de eventos de viajes. Descarga el mensaje, busca el usuario en Redis 
   y despacha los mapas puros al caso de uso."
  [channel use-case cache-service notifier]
  (let [subscribe (requiring-resolve 'langohr.basic/subscribe)
        ack       (requiring-resolve 'langohr.basic/ack)
        nack      (requiring-resolve 'langohr.basic/nack)
        reject    (requiring-resolve 'langohr.basic/reject)]
    (subscribe channel "travel.events.queue" {:auto-ack false}
               (fn [ch metadata ^bytes payload]
                 (try
                   (let [msg (json/parse-string (String. payload "UTF-8") true)]
                     (if (m/validate dtos/TravelDTO msg)
                       (let [user-id  (:organizerId msg)
                             user-raw ((:get-cache cache-service) (str "user:" user-id))]
                         (if user-raw
                           (let [user-data (json/parse-string user-raw true)]
                             (use-case {:travel msg :user user-data} notifier)
                             (ack ch (:delivery-tag metadata)))

                           (do
                             (println "Advertencia: Datos de usuario no encontrados en caché para ID:" user-id ". Reintentando...")
                             (nack ch (:delivery-tag metadata) false true))))

                       (do
                         (println "Error: Mensaje corrupto recibido en travel.events.queue" msg)
                         (reject ch (:delivery-tag metadata) false))))
                   (catch Exception e
                     (println "Error procesando el mensaje de RabbitMQ en travel-consumer:" (.getMessage e))
                     (reject ch (:delivery-tag metadata) false)))))))