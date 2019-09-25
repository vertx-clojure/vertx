;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.http
  "Enables `raw` access to the http facilites of vertx. If you want more
  clojure idiomatic api, refer to the `vertx.web` namespace."
  (:require [promesa.core :as p]
            [vertx.util :as vu])
  (:import
   io.vertx.core.Vertx
   io.vertx.core.Verticle
   io.vertx.core.Handler
   io.vertx.core.Future
   io.vertx.core.Context
   io.vertx.core.http.HttpServer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.HttpServerOptions))

(declare opts->http-server-options)
(declare resolve-handler)
(declare resolve-system)

;; --- Public Api

(defn server
  [vsm {:keys [handler] :as options}]
  (let [^Vertx vsm (resolve-system vsm)
        ^HttpServerOptions opts (opts->http-server-options options)
        ^HttpServer srv (.createHttpServer vsm opts)
        ^Handler handler (resolve-handler handler)]
    (doto srv
      (.requestHandler handler)
      (.listen))
    srv))

;; --- Impl

(defn resolve-system
  [o]
  (cond
    (instance? Vertx o) o
    (instance? Context o) (.owner ^Context o)
    :else (throw (ex-info "unexpected parameters" {}))))

(defn- opts->http-server-options
  [{:keys [host port]}]
  (let [opts (HttpServerOptions.)]
    (.setReuseAddress opts true)
    (.setReusePort opts true)
    (.setTcpNoDelay opts true)
    (when host (.setHost opts host))
    (when port (.setPort opts port))
    opts))

(defn- fn->handler
  [f]
  (reify Handler
    (handle [_ request]
      (f request))))

(defn- resolve-handler
  [handler]
  (cond
    (fn? handler) (fn->handler handler)
    (instance? Handler handler) handler
    :else (throw (ex-info "invalid handler" {}))))
