(ns timer
  (:require [vertx.timers :as vt]
            [vertx.core :as vc]
            [vertx.eventbus :as ve]))

(def echo-verticle*
  (letfn [(on-message [ctx message]
            (println (pr-str "receive:" message
                             "body" (:body message))))
          (on-start [ctx]
            (ve/consumer ctx "test.echo" on-message))]
    (vc/verticle {:on-start on-start}) ))

(defn abs [x] (Math/abs x))
(println "Vert.x test the timer api")
(let [system (vc/system)
      count (ref 0)]
  (vc/deploy! system echo-verticle*)
  (vt/schedule-periodic! system 50
                         (fn [] (ve/send! system "test.echo"
                                          (str "time up at " @count))
                           (dosync
                           (alter count + 1) )))

  (Thread/sleep 5000)
  ;; at this moment, count should be 100 absolutely won't be 1 or more
  (assert (<= (abs (- @count 100)) 1))


  (.close system))
