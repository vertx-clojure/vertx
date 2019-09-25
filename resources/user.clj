(ns user
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.tools.namespace.repl :as r]
   [clojure.walk :refer [macroexpand-all]]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [vertx.core :as vx]
   [vertx.eventbus :as vxe]
   [vertx.http :as vxh]
   [vertx.web :as vxw])
  (:import
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse))


(defstate system
  :start (vx/system)
  :stop (.close system))

(defn thr-name
  []
  (.getName (Thread/currentThread)))

;; --- Event Bus Verticle

;; (defn message-handler-verticle-fn
;;   [vsm ctx]
;;   (println (str "init: message-handler-verticle-fn on " (thr-name)))
;;   (letfn [(on-message [message]
;;             (println (pr-str "received:" (.body message)
;;                              "on" (thr-name)))
;;             (inc (.body message)))]
;;     (vxe/consume! vsm "test.topic" on-message)))

;; (defstate echo-verticle-id
;;   :start @(vx/deploy! system message-handler-verticle-fn {:instances 1}))

;; --- Http Server Verticle

(defn simple-http-handler
  [request]
  (let [^HttpServerResponse response (.response ^HttpServerRequest request)]
    (.setStatusCode response 200)
    (.end response "Hello world\n")))

(def http-verticle
  (letfn [(on-start [ctx state]
            (locking system
              (println (str "http-verticle: on-start " (thr-name))))
            (let [server (vxh/server (.owner ctx) {:handler simple-http-handler :port 2019})]
              {::stop #(.close server)}))
          (on-stop [ctx state]
            (locking system
              (println (str "http-verticle: on-start " (thr-name)))))]
    (vx/verticle {:on-start on-start
                  :on-stop on-stop})))

(defstate http-server-verticle
  :start (vx/deploy! system http-verticle {:instances 1}))

;; --- Http Web Handler

(defn simple-web-handler
  [ctx]
  {:status 200
   :body "hello world web\n"})

(def web-verticle
  (letfn [(on-init [ctx]
            (println "web-verticle: on-init " (thr-name)))

          (on-start [ctx state]
            (locking system
              (println (str "web-verticle: on-start " (thr-name))))
            (let [handler (vxw/wrap ctx simple-web-handler)
                  server (vxh/server ctx {:handler handler :port 2020})]
              {::stop #(.close server)}))

          (on-stop [ctx state]
            (prn "web-verticle: on-stop " (thr-name))
            (prn "web-verticle: on-stop ... " state))]

    (vx/verticle {:on-init on-init
                  :on-start on-start
                  :on-stop on-stop})))

(defstate web-server-verticle
  :start (vx/deploy! system web-verticle {:instances 4}))
