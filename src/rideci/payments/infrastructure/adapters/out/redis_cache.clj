(ns rideci.payments.infrastructure.adapters.out.redis-cache
  (:require [clojure.string :as str])
  (:import [javax.net.ssl SSLSocketFactory]
           [java.io PrintWriter InputStreamReader BufferedReader]))

(defn- load-env-var [key]
  (or (System/getenv key)
      (when-let [env-file (try (slurp ".env") (catch Exception _ nil))]
        (some (fn [line]
                (let [trimmed (str/trim line)]
                  (when (str/starts-with? trimmed (str key "="))
                    (str/replace-first trimmed (str key "=") ""))))
              (str/split-lines env-file)))))

(defn send-redis-cmd [cmd-string]
  (let [host (load-env-var "REDIS_HOST")
        port (Integer/parseInt (or (load-env-var "REDIS_PORT") "6380"))
        pass (load-env-var "REDIS_PASSWORD")
        factory (SSLSocketFactory/getDefault)
        socket (.createSocket factory host port)]
    (try
      (.startHandshake socket)
      (with-open [out (PrintWriter. (.getOutputStream socket) true)
                  in (BufferedReader. (InputStreamReader. (.getInputStream socket)))]
        (.println out (str "AUTH " pass))
        (let [auth-resp (.readLine in)]
          (if (not= auth-resp "+OK")
            (str "Error AUTH: " auth-resp)
            (do
              (.println out cmd-string)
              (.readLine in)))))
      (finally
        (.close socket)))))

(defn save-idempotency!
  "Intenta guardar la llave en Redis únicamente si no existe (NX) con un TTL.
   Devuelve true si la guardó con éxito, false si ya existía."
  [key val ttl-seconds]
  (let [resp (send-redis-cmd (str "SET payments:" key " " val " NX EX " ttl-seconds))] 
    (= resp "+OK")))
(defn delete-idempotency!
  [key]
  (let [resp (send-redis-cmd (str "DEL payments:" key))]
    (and (string? resp) (str/starts-with? resp ":") (not= resp ":0"))))