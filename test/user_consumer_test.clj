(ns user-consumer-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [rideci.payments.infrastructure.adapters.in.user-consumer :as sut]))

(deftest start-user-consumer-test
  (let [captured-callback (atom nil)
        cache-saved       (atom nil)
        mock-ch           "mock-channel"
        mock-meta         {:delivery-tag 1}

        mock-cache-service {:set-cache (fn [key val ttl]
                                         (reset! cache-saved {:key key :val val :ttl ttl}))}]

    (with-redefs [clojure.core/requiring-resolve
                  (fn [sym]
                    (if (= sym 'langohr.basic/subscribe)
                      (fn [_ _ _ callback] (reset! captured-callback callback))
                      nil))
                  m/validate (fn [_ msg] (not (:corruptmsg msg)))]

      (sut/start-user-consumer mock-ch mock-cache-service)

      (testing "Caso 1: Sincronización exitosa -> Guarda en cache con TTL"
        (let [handler @captured-callback
              payload (.getBytes "{\"user_id\":\"u-99\",\"name\":\"Juan\"}" "UTF-8")]
          (reset! cache-saved nil)
          (handler mock-ch mock-meta payload)
          (is (= "user:u-99" (:key @cache-saved)))
          (is (= 9600 (:ttl @cache-saved)))))

      (testing "Caso 2: Mensaje inválido por Malli -> No actualiza la cache"
        (let [handler @captured-callback
              payload (.getBytes "{\"corruptmsg\":true}" "UTF-8")]
          (reset! cache-saved nil)
          (handler mock-ch mock-meta payload)
          (is (nil? @cache-saved))))

      (testing "Caso 3: Error de parseo JSON -> Captura la excepción de forma segura"
        (let [handler @captured-callback
              payload (.getBytes "bad-json-syntax-str" "UTF-8")]
          (reset! cache-saved nil)
          (handler mock-ch mock-meta payload)
          (is (nil? @cache-saved)))))))