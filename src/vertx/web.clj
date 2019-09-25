;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web
  "High level api for http servers."
  (:require [promesa.core :as p]
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
(declare ->RequestContext)

(def ^:const ctx-internal-key
  "$$$clojure.vertx.state$$$")

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
                         (let [req (.request ^RoutingContext context)
                               res (.response ^RoutingContext context)
                               ctx (->RequestContext ctx-internal-key
                                                     context req res)]
                           (p/bind (p/do! (f ctx))
                                   (fn [res] (-handle-response res ctx)))))))
     router)))

;; (defn wrap-routes
;;   ([vsm routes] (wrap-routes vsm routes nil))
;;   ([vsm routes opts]
;;    (letfn [(handler [ctx]
;;              )]
;;      (wrap vsm handler opts))))

;; --- Impl

(defprotocol IAsyncBody
  (-handle-body [_ _]))

(defprotocol IAsyncResponse
  (-handle-response [_ _]))

(deftype RequestContext [^String key
                         ^RoutingContext ctx
                         ^HttpServerRequest req
                         ^HttpServerResponse res]
  clojure.lang.IDeref
  (deref [_]
    (.get ctx key))

  clojure.lang.IAtom
  (reset [_ val]
    (.put ctx key val))
  (swap [self f]
    (.reset self (f (.deref self))))
  (swap [self f x]
    (.reset self (f (.deref self) x)))
  (swap [self f x y]
    (.reset self (f (.deref self) x y)))
  (swap [self f x y more]
    (.reset self (apply f (.deref self) x y more)))

  clojure.lang.ILookup
  (valAt [self k]
    (case k
      (:req :request) req
      (:res :response) res
      :method (.rawMethod req)
      ;; :query-params (get-memoized-query-params req self)
      ;; :headers (get-memoized-headers req self)
      ;; :cookies (get-memoized-cookies req self)
      nil))

  (valAt [this k default]
    (or (.valAt this k) default)))

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


