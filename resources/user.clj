(ns user
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as r]
   [clojure.walk :refer [macroexpand-all]]
   [integrant.core :as ig]
   [pohjavirta.server :as pohjavirta]
   [promesa.core :as p]
   [reitit.core :as rt]
   [vertx.core :as vx]
   [vertx.eventbus :as vxe]
   [vertx.http :as vxh]
   [vertx.web :as vxw]
   [vertx.web.interceptors :as vxi])
  (:import
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse))

(declare thr-name)

;; --- System

(defmethod ig/init-key :system
  [_ options]
  (vx/system options))

(defmethod ig/halt-key! :system
  [_ system]
  (.close system))

;; --- Echo Verticle (using eventbus)

(def echo-verticle
  (letfn [(on-message [message]
            (println (pr-str "received:" message
                             "on" (thr-name)))
            (:body message))
          (on-start [ctx]
            (vxe/consumer ctx "test.echo" on-message))]

    (vx/verticle {:on-start on-start})))

(defmethod ig/init-key :verticle/echo
  [_ {:keys [system] :as options}]
  @(vx/deploy! system echo-verticle options))

(defmethod ig/halt-key! :verticle/echo
  [_ disposable]
  (.close disposable))

;; --- Echo Verticle Actor (using eventbus)

;; This is the same as the previous echo verticle, it just reduces the
;; boilerplate of creating the consumer.

(def echo-actor-verticle
  (letfn [(on-message [message]
            (println (pr-str "received:" (.body message)
                             "on" (thr-name)))
            (.body message))]
    (vx/actor "test.echo2" {:on-message on-message})))

(defmethod ig/init-key :verticle/echo-actor
  [_ {:keys [system] :as options}]
  @(vx/deploy! system echo-actor-verticle options))

(defmethod ig/halt-key! :verticle/echo-actor
  [_ disposable]
  (.close disposable))

;; --- Http Server Verticle

(def http-verticle
  (letfn [(handler [req]
            (let [^HttpServerResponse response (.response ^HttpServerRequest req)]
              (.setStatusCode response 200)
              (.end response "Hello world vtx\n")))

          (on-start [ctx]
            (let [server (vxh/server ctx {:handler handler :port 2019})]
              {::stop #(.close server)}))]
    (vx/verticle {:on-start on-start})))

(defmethod ig/init-key :verticle/http
  [_ {:keys [system port] :as options}]
  @(vx/deploy! system http-verticle options))

(defmethod ig/halt-key! :verticle/http
  [s disposable]
  (.close disposable))

;; --- Http Web Handler

(def web-verticle
  (letfn [(handler [ctx]
            {:status 200
             :body "hello world web\n"})

          (on-start [ctx]
            (let [handler (vxw/wrap ctx handler)]
              (vxh/server ctx {:handler handler :port 2020})))]

    (vx/verticle {:on-start on-start})))

(defmethod ig/init-key :verticle/web
  [_ {:keys [system] :as options}]
  @(vx/deploy! system web-verticle options))

(defmethod ig/halt-key! :verticle/web
  [_ disposable]
  (.close disposable))

;; --- Web Router Verticle

(def web-router-verticle
  (letfn [(handler [ctx]
            (let [params (:path-params ctx)]
              {:status 200
               :cookies {"foo-baz" {:value "test" :path "/foo"}}
               :body (str "hello " (:name params) "\n")}))

          (on-error [ctx err]
            (prn "err" err))

          (on-start [ctx]
            (let [routes [["/foo/bar/:name" {:get handler}]]
                  cors-opts {:origin "*"
                             :allow-credentials true
                             :allow-methods #{:post :get :patch :head :options :put}}

                  router (rt/router routes {:data {:interceptors [(vxi/cookies)
                                                                  (vxi/headers)
                                                                  (vxi/cors cors-opts)]}})
                  handler (vxw/wrap-router ctx router)]
              (vxh/server ctx {:handler handler :port 2021})))]

    (vx/verticle {:on-start on-start :on-error on-error})))

(defmethod ig/init-key :verticle/web-router
  [_ {:keys [system] :as options}]
  @(vx/deploy! system web-router-verticle options))

(defmethod ig/halt-key! :verticle/web-router
  [_ disposable]
  (.close disposable))

;; --- pohjavirta

(defn handler [req]
  (let [method (:request-method req)
        path (:uri req)]
    {:status 200
     :body "hello world poh\n"}))

(defmethod ig/init-key :pohjavirta
  [& args]
  (let [instance (pohjavirta/create #'handler {:port 2022 :io-threads 4})]
    (pohjavirta/start instance)
    instance))

(defmethod ig/halt-key! :pohjavirta
  [_ server]
  (pohjavirta/stop server))

;; --- Config

(def config
  {:system {:threads 4}
   :verticle/http {:system (ig/ref :system) :instances 1}
   :verticle/web {:system (ig/ref :system) :instances 1}
   :verticle/web-router {:system (ig/ref :system) :instances 1}
   :verticle/echo {:system (ig/ref :system) :instances 1}})

(def state nil)

;; --- Repl

(defn start
  []
  (alter-var-root #'state (fn [state]
                            (when (map? state) (ig/halt! state))
                            (ig/init config)))
  :started)

(defn stop
  []
  (when (map? state) (ig/halt! state))
  (alter-var-root #'state (constantly nil))
  :stoped)

(defn restart
  []
  (stop)
  (r/refresh :after 'user/start))

(defn- run-test
  ([] (run-test #"^vertx-tests.*"))
  ([o]
   (r/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

;; --- Helpers

(defn thr-name
  []
  (.getName (Thread/currentThread)))

