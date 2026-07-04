(ns rideci.payments.infrastructure.adapters.out.postgres-repo
  (:require [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs] 
            [rideci.payments.infrastructure.ports.payment-repository :refer [IPaymentRepository]])
  (:import [com.zaxxer.hikari HikariDataSource]))

(defn create-datasource []
  (let [db-spec {:jdbcUrl         (or (System/getenv "DB_URL") (System/getProperty "DB_URL"))
                 :username        (or (System/getenv "DB_USER") (System/getProperty "DB_USER"))
                 :password        (or (System/getenv "DB_PASSWORD") (System/getProperty "DB_PASSWORD"))
                 :driverClassName "org.postgresql.Driver"}]
    (connection/->pool HikariDataSource db-spec)))

(deftype PostgresRepository [ds]
  IPaymentRepository
  (save! [_ tx]
    (jdbc/execute-one! ds
                       ["INSERT INTO transactions 
                         (id, travel_id, amount, status, payment_method, idempotency_key, version, trip_name, user_id, user_email, user_phone, user_role, created_at) 
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamp with time zone)"
                        (:id tx)
                        (:travel-id tx)
                        (:amount tx)
                        (str (:status tx))
                        (str (:payment-method tx))
                        (:idempotency-key tx)
                        (:version tx)
                        (:trip-name tx)
                        (:user-id tx)
                        (:user-email tx)
                        (:user-phone tx)
                        (:user-role tx)
                        (:created-at tx)]
                       {:builder-fn rs/as-unqualified-maps})) 

  (find-by-id [_ id]
    (jdbc/execute-one! ds
                       ["SELECT * FROM transactions WHERE id = ?" id]
                       {:builder-fn rs/as-unqualified-maps}))

  (find-by-user-id [_ user-id]
    (jdbc/execute! ds
                   ["SELECT * FROM transactions WHERE user_id = ? ORDER BY created_at DESC" (str user-id)]
                   {:builder-fn rs/as-unqualified-maps})) 

  (update-status-atomic! [_ id new-status current-version]
    (let [result (jdbc/execute-one! ds
                                    ["UPDATE transactions 
                                      SET status = ?, version = version + 1 
                                      WHERE id = ? AND version = ?
                                      RETURNING id"
                                     (str new-status) id current-version]
                                    {:builder-fn rs/as-unqualified-maps})] 
      (if result
        1
        (throw (ex-info "Conflict: La transacción no existe o la versión cambió simultáneamente"
                        {:id id :status 409}))))))

(defn save! [repo tx] (.save! repo tx))
(defn find-by-id [repo id] (.find-by-id repo id))
(defn find-by-user-id [repo user-id] (.find-by-user-id repo user-id))
(defn update-status-atomic! [repo id new-status current-version]
  (.update-status-atomic! repo id new-status current-version))