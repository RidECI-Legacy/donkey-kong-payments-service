(ns rideci.payments.configuration.rabbitmq
  (:require
   [rideci.payments.configuration.env]
   [langohr.core :as rmq]
   [langohr.channel :as lch]
   [langohr.exchange :as lexch])) 

(defn connect-and-open-channel []
  (let [uri (or (System/getenv "RABBITMQ_URL")
                (System/getProperty "RABBITMQ_URL"))]
    (let [conn (rmq/connect {:uri uri})
          ch (lch/open conn)]
      
      (lexch/declare ch "notifications.exchange" "topic" {:durable true})

      {:conn conn
       :ch ch})))