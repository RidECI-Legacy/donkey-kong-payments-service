(ns rideci.payments.configuration.env
  (:require [clojure.string :as str]))

(defn load-env! []
  (let [lines (str/split-lines (slurp ".env"))]
    (doseq [line lines]
      (let [clean-line (str/trim line)] 
        (when (and (not (str/blank? clean-line))
                   (not (str/starts-with? clean-line "#")))
          (let [[k v] (str/split clean-line #"=")]
            (when (and k v)
              (System/setProperty (str/trim k) (str/trim v)))))))))

(load-env!)