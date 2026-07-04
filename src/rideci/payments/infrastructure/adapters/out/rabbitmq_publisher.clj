(ns rideci.payments.infrastructure.adapters.out.rabbitmq-publisher
  (:require [langohr.basic :as lbasic]
            [cheshire.core :as json]
            [rideci.payments.domain.dtos :as dtos]
            [malli.core :as m]))

(defn create-notifier [channel]
  {
   :notify! (fn [payload]
              (if (m/validate dtos/NotificationDTO payload)
                (lbasic/publish channel
                                "notifications.exchange" 
                                "payment.status.updated" 
                                (json/generate-string payload)
                                {:persistent true        
                                 :content-type "application/json"})
                
                (throw (ex-info "Payload de notificación inválido"
                                {:errors (m/explain dtos/NotificationDTO payload)}))))})