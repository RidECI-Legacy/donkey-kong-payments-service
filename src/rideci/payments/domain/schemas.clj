(ns rideci.payments.domain.schemas)

(def Transaction
  [:map
   [:id :string]
   [:travel-id :string]
   [:trip-name :string]
   [:amount :double]
   [:currency {:default "COP"} :string]
   [:status [:enum "pending" "processing" "completed" "failed" "cancelled" "pending_cash_verification"]]
   [:payment-method [:enum "nequi" "card" "cash"]]
   [:version :int]
   [:idempotency-key :string]
   [:user-id :int]
   [:user-email :string]
   [:user-phone :string]
   [:user-role [:enum "DRIVER" "PASSENGER" "COMPANION"]]
   [:created-at :string]])
