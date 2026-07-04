(ns rideci.payments.infrastructure.ports.payment-repository)

(defprotocol IPaymentRepository
  (save! [this tx]) 
  (find-by-id [this id]) 
  (find-by-user-id [this user-id]) 
  (update-status-atomic! [this id new-status current-version]))
   