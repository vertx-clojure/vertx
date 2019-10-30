;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web
  "High level api for http servers."
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [sieppari.core :as sp]
            [sieppari.async.promesa]
            [reitit.core :as r]
            [vertx.http :as vxh]
            [vertx.util :as vu])
  (:import
   clojure.lang.Keyword
   clojure.lang.IPersistentMap
   clojure.lang.MapEntry
   java.util.Map$Entry
   io.vertx.core.Vertx
   io.vertx.core.Handler
   io.vertx.core.Future
   io.vertx.core.http.Cookie
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
(declare ->Response)

;; --- Public Api

(s/def ::wrap-handler
  (s/or :fn fn?
        :vec (s/every fn? :kind vector?)))

(def lowercase-keys-t
  (map (fn [^Map$Entry entry]
         (MapEntry. (.toLowerCase (.getKey entry)) (.getValue entry)))))

(defn- make-ctx
  [^RoutingContext context]
  (let [^HttpServerRequest request (.request ^RoutingContext context)
        ^HttpServerResponse response (.response ^RoutingContext context)
        method (-> request .rawMethod .toLowerCase keyword)
        headers (into {} lowercase-keys-t (-> request .headers))
        path (.path request)]
    (->RequestContext method headers path request response context)))

(defn wrap
  "Wraps a user defined funcion based handler into a vertx-web aware
  handler (with support for multipart uploads.

  If the handler is a vector, the sieppari intercerptos engine will be used
  to resolve the execution of the interceptors + handler."
  ([vsm f] (wrap vsm f nil))
  ([vsm f options]
   (s/assert ::wrap-handler f)
   ;; TODO: add error handling
   (let [^Vertx vsm (vu/resolve-system vsm)
         ^Router router (Router/router vsm)
         ^Route route (.route router)]

     (.handler route (BodyHandler/create true))
     (.handler route (reify Handler
                       (handle [_ context]
                         (let [ctx (make-ctx context)]
                           (-> (p/do! (f ctx))
                               (p/then' #(-handle-response % ctx))
                               (p/catch #(do (prn %) (.fail (:context ctx) %))))))))
     router)))

(defn- default-handler
  [ctx]
  (if (::match ctx)
    {:status 405}
    {:status 404}))

(defn- run-chain
  [ctx chain handler]
  (let [d (p/deferred)]
    (sp/execute (conj chain handler) ctx #(p/resolve! d %) #(p/reject! d %))
    d))

(defn- router-handler
  [router {:keys [path method] :as ctx} options]
  (let [{:keys [data path-params] :as match} (r/match-by-path router path)
        handler-fn (get data method default-handler)
        interceptors (get data :interceptors)
        ctx (assoc ctx ::match match :path-params path-params)]
    (if (empty? interceptors)
      (handler-fn ctx)
      (run-chain ctx interceptors handler-fn))))

(defn wrap-router
  "Wraps a reitit router instance in a vertx-web aware handler."
  ([vsm router] (wrap-router vsm router nil))
  ([vsm router options]
   (s/assert r/router? router)
   (let [handler #(router-handler router % options)]
     (wrap vsm handler options))))

(defn rsp
  "Creates a response record (faster than simple map)."
  ([status] (->Response status "" nil))
  ([status body] (->Response status body nil)))

;; --- Impl

(defrecord RequestContext [^Keyword method
                           ^IPersistentMap headers
                           ^String path
                           ^HttpServerRequest request
                           ^HttpServerRequest response
                           ^RoutingContext context])

(defrecord Response [status body headers])

(defprotocol IAsyncResponse
  (-handle-response [_ _]))

(extend-protocol IAsyncResponse
  clojure.lang.IPersistentMap
  (-handle-response [data ctx]
    (let [status (or (:status data) 200)
          headers (:headers data)
          cookies (:cookies data)
          body (:body data)
          res (:response ctx)]
      (.setStatusCode ^HttpServerResponse res status)
      (run! (fn [[name value]]
              (.putHeader ^HttpServerResponse res
                          ^String name
                          ^String value))
            headers)
      (-handle-body body res))))

(defprotocol IAsyncBody
  (-handle-body [_ _]))

(extend-protocol IAsyncBody
  nil
  (-handle-body [data res]
    (.putHeader ^HttpServerResponse res "content-length" "0")
    (.end ^HttpServerResponse res))

  String
  (-handle-body [data res]
    (let [length (count data)]
      (.putHeader ^HttpServerResponse res "content-length" (str length))
      (.end ^HttpServerResponse res data))))
