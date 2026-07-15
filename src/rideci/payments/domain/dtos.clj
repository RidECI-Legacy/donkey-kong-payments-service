(ns rideci.payments.domain.dtos)

(def TravelDTO
  [:map
   [:travel_id :string]
   [:trip_name :string]
   [:organizerId :int]
   [:driverId {:optional true} :int]
   [:passengersId [:vector :int]]
   [:estimatedCost :double]])

(def UserDTO
  [:map
   [:user_id :int]
   [:email :string]
   [:phone :string]
   [:role [:enum "DRIVER" "PASSENGER" "COMPANION"]]])

(def NotificationDTO
  [:map
   [:event :string]               
   [:travel-id :string]
   [:trip-name :string]
   [:user-email :string]
   [:user-phone :string]
   [:amount :double]
   [:currency :string]
   [:status :string]              
   [:timestamp :string]])         