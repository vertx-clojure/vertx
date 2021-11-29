(ns route-test
  (:require [vertx.core :as vc]
            [vertx.web :as web]
            [vertx.http :as http]
            [vertx.promise :as p]
            [vertx.web.client :as cli]))

(def sys (vc/system))

(defn api [req]
  (p/resolved {"code" 0}))

(def router (web/build-route
             [["/api/route" api]]))

(http/server sys {:handler (web/handler sys
                                        `router)
                  :port 8091})

(Thread/sleep 500)

(def c (cli/create sys))

(defn my-test [target]
  (-> ((cli/request c :GET "http://localhost:8091/api/route"))
      (.join)
      (:body)
      (.toJsonObject)
      ((fn [b] (println b) b))
      (.getLong "code")
      (= target)
      (assert "not right")))

(try
  (my-test 0)

  (defn api [req]
    (p/resolved {"code" 1}))
  (Thread/sleep 201)
  (my-test 0)

  (def router (web/build-route
               [["/api/route" api]]))
  (Thread/sleep 201)
  (my-test 1)
  (defn api2 [req]
    (p/resolved {"code" 2}))

  (def router (web/build-route
               [["/api/route" api2]]))
  (my-test 1)
  (Thread/sleep 201)
  (my-test 2)
  (.close sys)
  (catch Exception e
    (.close sys)
    (println e)
    (throw e)))
