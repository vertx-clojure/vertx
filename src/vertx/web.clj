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
(declare -run-handler)
(declare ->RequestContext)
(declare ->Response)

;; --- Public Api

(s/def ::wrap-handler
  (s/or :fn fn?
        :vec (s/every fn? :kind vector?)))

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
                         (let [^HttpServerRequest request (.request ^RoutingContext context)
                               ^HttpServerResponse response (.response ^RoutingContext context)
                               method (-> request .rawMethod .toLowerCase keyword)
                               path (.path request)
                               ctx (->RequestContext method path request response context)]
                           (-> (-run-handler f ctx)
                               (p/then' #(-handle-response % ctx))
                               (p/catch (fn [err]
                                          (.fail context err))))))))
     router)))

(declare router-handler)

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
                           ^String path
                           ^HttpServerRequest request
                           ^HttpServerRequest response
                           ^RoutingContext context])

(defrecord Response [status body headers])

(defprotocol IAsyncBody
  (-handle-body [_ _]))

(defprotocol IAsyncResponse
  (-handle-response [_ _]))

(defprotocol IRunHandler
  (-run-handler [_ ctx]))

(extend-protocol IAsyncResponse
  clojure.lang.IPersistentMap
  (-handle-response [data ctx]
    (let [status (or (:status data) 200)
          cookies (:cookies data)
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
    (f ctx))

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

(defn- router-handler
  [router {:keys [path method] :as ctx} options]
  (if-let [{:keys [data path-params] :as match} (r/match-by-path router path)]
    (let [handler (get data method)
          interceptors (get data :interceptors)
          ctx (assoc ctx ::match match :path-params path-params)]
      (cond
        (nil? handler) (default-handler ctx)
        (empty? interceptors) (handler ctx)
        :else (-run-handler (conj interceptors handler) ctx)))
    (default-handler ctx)))

;; --- Cookies Interceptor

(def cookies-interceptor
  (letfn [(parse-cookie [^Cookie item]
            [(.getName item)
             {:value (.getValue item)}])
          (encode-cookie [name data]
            (cond-> (Cookie/cookie ^String name ^String (:value data))
              (:http-only data) (.setHttpOnly true)
              (:domain data) (.setDomain (:domain data))
              (:path data) (.setPath (:path data))
              (:secure data) (.setSecure true)))
          (add-cookie [^HttpServerResponse res [name data]]
            (.addCookie res (encode-cookie name data)))
          (enter [data]
            (let [^HttpServerRequest req (get-in data [:request :request])
                  cookies (into {} (map parse-cookie) (vals (.cookieMap req)))]
              (update data :request assoc :cookies cookies)))
          (leave [data]
            (let [cookies (get-in data [:response :cookies])
                  res (get-in data [:request :response])]
              (when (map? cookies)
                (run! (partial add-cookie res) cookies))
              data))]
    {:enter enter
     :leave leave}))

