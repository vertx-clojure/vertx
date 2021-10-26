(ns web-route
  (:require [vertx.core :as vc]
            [vertx.http :as vh]
            [vertx.web  :as vw]
            [jsonista.core :as j]
            [vertx.web.client :as cli]))

;; start the http server

(def system (vc/system))



(letfn [(handler [req] {:status 200
                        :body (j/write-value-as-string
                               {:method (:method req)
                                :path (:path req)
                                :body (:body req)} )})
        (on-start [ctx]
                         (let [route [["/" {:all handler}]]
                               route-handler (vw/handler ctx (vw/router route)) ]
                          (vh/server ctx {:handler route-handler
                            :port 8095} )))
       ]
  (vc/deploy! system (vc/verticle {:on-start on-start}))
  ;; execute the cli
  (println "web server start")
;;  (request-test)
  (println "client start")
  ;; sleep and kill
  (Thread/sleep 100)
                                        ;  (.close system) )
  )

