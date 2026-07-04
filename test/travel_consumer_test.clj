(ns travel-consumer-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [rideci.payments.infrastructure.adapters.in.travel-consumer :as sut]))

(deftest start-consumer-test
  (let [captured-callback (atom nil)
        ack-called        (atom nil)
        nack-called       (atom nil)
        reject-called     (atom nil)
        use-case-called   (atom nil)
        cache-return      (atom "{\"id\":1}")

        mock-ch "mock-channel"
        mock-meta {:delivery-tag 42}
        mock-notifier "mock-notifier"
        mock-cache-service {:get-cache (fn [_] @cache-return)}]

    (with-redefs [clojure.core/requiring-resolve
                  (fn [sym]
                    (cond
                      (= sym 'langohr.basic/subscribe) (fn [_ _ _ callback] (reset! captured-callback callback))
                      (= sym 'langohr.basic/ack)       (fn [_ tag] (reset! ack-called tag))
                      (= sym 'langohr.basic/nack)      (fn [_ tag _ requeue] (reset! nack-called {:tag tag :requeue requeue}))
                      (= sym 'langohr.basic/reject)    (fn [_ tag requeue] (reset! reject-called {:tag tag :requeue requeue}))
                      :else nil))
                  m/validate (fn [_ msg] (not (:corruptmsg msg)))]

      (sut/start-consumer mock-ch (fn [_ _] (reset! use-case-called true)) mock-cache-service mock-notifier)

      (testing "Caso 1: Procesamiento exitoso (ACK)"
        (let [handler @captured-callback
              payload (.getBytes "{\"organizerId\":123,\"trip_name\":\"ECI\"}" "UTF-8")]
          (reset! cache-return "{\"id\":1}")
          (reset! ack-called nil)
          (reset! use-case-called nil)
          (handler mock-ch mock-meta payload)
          (is (= true @use-case-called))
          (is (= 42 @ack-called))))

      (testing "Caso 2: Usuario ausente en cache (NACK)"
        (let [handler @captured-callback
              payload (.getBytes "{\"organizerId\":999,\"trip_name\":\"ECI\"}" "UTF-8")]
          (reset! cache-return nil)
          (reset! nack-called nil)
          (handler mock-ch mock-meta payload)
          (is (= {:tag 42 :requeue true} @nack-called))))

      (testing "Caso 3: Mensaje corrupto por Malli (REJECT)"
        (let [handler @captured-callback
              payload (.getBytes "{\"corruptmsg\":true}" "UTF-8")]
          (reset! reject-called nil)
          (handler mock-ch mock-meta payload)
          (is (= {:tag 42 :requeue false} @reject-called))))

      (testing "Caso 4: Excepcion en parseo JSON (REJECT)"
        (let [handler @captured-callback
              payload (.getBytes "broken-json-string-!!!" "UTF-8")]
          (reset! reject-called nil)
          (handler mock-ch mock-meta payload)
          (is (= {:tag 42 :requeue false} @reject-called)))))))