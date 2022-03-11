(ns web-r
  (:require [vertx.web :as web]
            [vertx.core :as vc]
            [vertx.http :as vh]
            [vertx.promise :as p]
            [vertx.web.client :as cli])
  (:import io.vertx.core.json.JsonObject
           io.vertx.ext.web.handler.BodyHandler))

(def sys (vc/system))

(defn a [req]
  (let [header (:headers req)
        body (:body req)
        data (JsonObject. {"h" header "b" (str (type body))})]
    (println data)
    (p/resolved data)))

(def route [["/a" `a]])

(let [route-reg (web/build-route route)]
  (vh/server sys {:handler (web/handler sys
                                        (web/handle-error 405 (fn [e rtx]
                                                                (println e)
                                                                (.end rtx "NOT MATCH")))
                                        (web/handle-error 500 (fn [e rtx]
                                                                (println e)
                                                                (.end rtx "ERROR")))
                                        (web/handle-error -1 (fn [e rtx]
                                                               (println e)
                                                               (.end rtx "ERROR")))
                                        route-reg)
                  :port 8095}))

(let [c (cli/create sys)
      c (cli/session c)
      r (cli/request c {:host "localhost"
                        :method :POST
                        :port 8095
                        :uri "/a"})]
  (.join (r)))
