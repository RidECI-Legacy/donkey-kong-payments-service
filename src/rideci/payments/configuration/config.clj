(ns rideci.payments.configuration.config
  (:import (io.github.cdimascio.dotenv Dotenv)))

(defonce ^:private dotenv (Dotenv/load))

(defn get-env [key]
  (.get dotenv key))