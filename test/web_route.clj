(ns web-route
  (:import io.vertx.core.json.JsonArray
           io.vertx.core.json.JsonObject)
  (:require [vertx.core :as vc]
            [vertx.http :as vh]
            [promesa.core :as p]
            [vertx.web  :as vw]
            [jsonista.core :as j]
            [vertx.web.client :as cli]))

;; start the http server

(def system (vc/system))


(def name (ref nil))

(letfn [(handler [req]
          (dosync (ref-set name
                           (.getString (.toJsonObject (:body req)) "user" "none")))
          (println "request body -> " (.toString (.toJsonObject (:body req))))
          {:status 200
           :body (j/write-value-as-string
                  {:method (:method req)
                   :path (:path req)
                   :body (:body req)
                   :headers (:headers req)
                   } )})
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
  ;; sleep and kill
  (Thread/sleep 1000)
                                        ;  (.close system) )
  )

;; use the web client to send the request

(println "client start")
(def body (ref nil))

(let [c (cli/create system)
      r (cli/request c 'POST "http://localhost:8095")
      p (r :json {:user "me" :age 24})]
  (p/then p (fn [data]
              (println "response -> " data)
              (dosync
               (ref-set body data))
              )))

(Thread/sleep 1000)


(defn- toJson
  "convert the data into Json for the further use"
  [data]
  ;; check if clojure struct, if clojure struct be, convert it
  ;; if none clojure struct simply
  (cond
    (string? data) data
    (char? data) data
    (number? data) (BigDecimal. (str data))
    (keyword? data) (str data)
    (symbol? data) (str data)
    ;; if vector, it should convert into JsonArray
    (vector? data) (map (fn [array x] (.add array (toJson x))
                          array)
                        (JsonArray.)
                          data)

    (list? data)   (map (fn [array x] (.add array (toJson x))
                          array)
                        (JsonArray.)
                         data)

    (set? data)    (reduce (fn [array x] (.add array (toJson x))
                             array)
                           (JsonArray.)
                           data)

    ;; if map, it should convert into a JsonObject and add other into it
    (map? data)    (reduce (fn [js [x y]]
                             (.put js (str x) (toJson y))
                             js)
                           (JsonObject.)
                           data)

    true (JsonObject/mapFrom data)
))

(println "json -> " (.toString (toJson {:me "ok"})))

(assert @body "require the response")

(assert (= @name "me") (str "request name seems wrong, here got " @name))
