;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web
  "High level api for http servers."
  (:require [promesa.core :as p]
            [sieppari.core :as sp]
            [reitit.core :as r]
            [vertx.http :as vxh]
            [vertx.util :as vu])
  (:import
   io.vertx.core.Vertx
   io.vertx.core.Handler
   io.vertx.core.Future
   io.vertx.core.http.HttpServer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.HttpServerOptions
   io.vertx.ext.web.Route
   io.vertx.ext.web.Router
   io.vertx.ext.web.RoutingContext
   io.vertx.ext.web.handler.BodyHandler))

;; --- Constants & Declarations

(declare -handle-response)
(declare -handle-body)
(declare -run-handler)
(declare ->RequestContext)

;; --- Public Api

(defn wrap
  ([vsm f] (wrap vsm f nil))
  ([vsm f opts]
   (let [^Vertx vsm (vxh/resolve-system vsm)
         ^Router router (Router/router vsm)
         ^Route route (.route router)]
     (.handler route (BodyHandler/create true))
     (.handler route (reify Handler
                       (handle [_ context]
                         (let [^HttpServerRequest req (.request ^RoutingContext context)
                               ^HttpServerResponse res (.response ^RoutingContext context)
                               method (-> req .rawMethod .toLowerCase keyword)
                               path (.path req)
                               ctx (->RequestContext method path req res)]
                           (p/then' (-run-handler f ctx)
                                    (fn [res] (-handle-response res ctx)))))))
     router)))

(declare router-handler)

(defn wrap-router
  ([vsm router] (wrap-router vsm router nil))
  ([vsm router opts]
   (as-> #(router-handler router % opts) handler
     (wrap vsm handler opts))))

;; --- Impl

(defprotocol IAsyncBody
  (-handle-body [_ _]))

(defprotocol IAsyncResponse
  (-handle-response [_ _]))

(defprotocol IRunHandler
  (-run-handler [_ ctx]))

(defrecord RequestContext [method path request response])

(extend-protocol IAsyncResponse
  clojure.lang.IPersistentMap
  (-handle-response [data ctx]
    (let [status (or (:status data) 200)
          body (or (:body data) "")
          res (:response ctx)]
      (.setStatusCode ^HttpServerResponse res status)
      (-handle-body body res))))

(extend-protocol IAsyncBody
  String
  (-handle-body [data res]
    (.end ^HttpServerResponse res data)))

(extend-protocol IRunHandler
  clojure.lang.Fn
  (-run-handler [f ctx]
    (p/do! (f ctx)))

  clojure.lang.IPersistentVector
  (-run-handler [v ctx]
    (let [d (p/deferred)]
      (sp/execute v ctx #(p/resolve! d %) #(p/reject! d %))
      d)))

(def noop (constantly nil))

(defn- default-handler
  [ctx]
  (if-let [match (::match ctx)]
    {:status 405 :body ""}
    {:status 404 :body ""}))

(defn- error-handler
  [ctx]
  {:status 500 :body ""})

(defn- router-handler
  [router {:keys [path method] :as ctx} options]
  (try
    (if-let [{:keys [data path-params] :as match} (r/match-by-path router path)]
      (let [handler (get data method noop)
            ctx (assoc ctx ::match match :path-params path-params)]
        (or (handler ctx) (default-handler ctx)))
      (default-handler ctx))
    (catch Throwable e
      (error-handler ctx e))))
