(ns user
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.tools.namespace.repl :as r]
   [clojure.walk :refer [macroexpand-all]]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [reitit.core :as rt]
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

(def http-verticle
  (letfn [(handler [req]
            (let [^HttpServerResponse response (.response ^HttpServerRequest req)]
              (.setStatusCode response 200)
              (.end response "Hello world\n")))

          (on-start [ctx state]
            (let [server (vxh/server (.owner ctx) {:handler handler :port 2019})]
              {::stop #(.close server)}))]

    (vx/verticle {:on-start on-start})))

(defstate http-verticle*
  :start (vx/deploy! system http-verticle {:instances 1}))

;; --- Http Web Handler

(def web-verticle
  (letfn [(handler [ctx]
            {:status 200
             :body "hello world web\n"})

          (on-start [ctx state]
            (let [handler (vxw/wrap ctx handler)
                  server (vxh/server ctx {:handler handler :port 2020})]
              {::stop #(.close server)}))]

    (vx/verticle {:on-start on-start})))

(defstate web-verticle*
  :start (vx/deploy! system web-verticle {:instances 1}))

;; --- Web Router Verticle

(def sample-interceptor
  {:enter (fn [data]
            ;; (prn "sample-interceptor:enter")
            (p/deferred data))
   :leave (fn [data]
            ;; (prn "sample-interceptor:leave")
            (p/deferred data))})

(def web-router-verticle
  (letfn [(handler [ctx]
            (let [params (:path-params ctx)]
              {:status 200
               :body (str "hello " (:name params) "\n")}))

          (on-start [ctx state]
            (let [routes [["/foo/bar/:name" {:interceptors [sample-interceptor]
                                             :get handler}]]
                  router (rt/router routes)
                  handler (vxw/wrap-router ctx router)
                  server (vxh/server ctx {:handler handler :port 2021})]
              {::stop #(.close server)}))]

    (vx/verticle {:on-start on-start})))

(defstate web-router-verticle*
  :start (vx/deploy! system web-router-verticle {:instances 4}))
