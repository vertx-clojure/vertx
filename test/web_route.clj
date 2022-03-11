(ns web-route
  (:import io.vertx.core.json.JsonArray
           io.vertx.core.json.JsonObject)
  (:require [vertx.core :as vc]
            [vertx.http :as vh]
            [promesa.core :as p]
            [vertx.web  :as vw]
;;            [jsonista.core :as j]
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


;; init
(def s (vc/system))

(defn a [ctx]
  (let [shopId (.get (:param ctx) "shopId")]
  (println "data got -> " (type shopId))
  (vertx.promise/resolved {"shopId" (+ 1 shopId)})))
;; create server
(vh/server s
           {:handler  (vw/handler s
                                  (vw/handle-error 500 (fn [e ctx]
                                                      (println e)
                                                      (.json ctx {"code" 0
                                                                  "error" (str e)})))
                                  (vw/build-route
                                   [["/api/test" a]] ))
  :port 8095})
(.close s)




(ns a)
(defn test-one
  [ctx]
  (println "here we go")
  (vertx.promise/resolved {"code" 1
                           "time" (System/currentTimeMillis)}))

(def r1 [["/api/test" test-one]])

(ns b)


(defn context-handler
  [ctx]
  (println "i should decode the json")
;;  (.json (:routing-context ctx) {"code" 1})
  (.next (:routing-context ctx))
  )


(def route [{:routes a/r1
             ;;:path "/api/test"
             :handler [context-handler]
             }])


(def s (vertx.core/system))
(println route)
(vertx.http/server s
                   {:handler (vertx.web/handler
                              s
                              (vertx.web/handle-error 500
                                                       (fn [e ctx]
                                                         (println e)
                                                         (.json ctx {"code" -1 "error" (str e)})))
                              (vertx.web/build-route b/route)
                              )
 :port 8085})
(.close s)

(def ROUTER-LIST
  ;; for debug and some special use
  ;; TODO make it better
  (java.util.concurrent.ConcurrentHashMap.)
