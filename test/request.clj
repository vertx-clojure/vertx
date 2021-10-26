(ns request
  (:require [vertx.core :as vc]
            [vertx.http :as vh]
            [vertx.web  :as vw]
            [jsonista.core :as j]
            [vertx.web.client :as cli]))

;; start the http server

(def system (vc/system))


(try
  (let [c (cli/create system)
        r (cli/request c 'GET "www.baidu.com" "")]
    (promesa.core/then (r)
                       (fn [res] (println res))
                       ))
  (catch java.lang.Exception e
    (.printStackTrace e))
  )

(Thread/sleep 3000)
