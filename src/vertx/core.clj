;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.core
  (:require [promesa.core :as p]
            [vertx.util :as vu])
  (:import io.vertx.core.Vertx
           io.vertx.core.VertxOptions
           io.vertx.core.Verticle
           io.vertx.core.Handler
           io.vertx.core.Future
           io.vertx.core.DeploymentOptions
           java.util.function.Supplier))

(declare opts->deployment-options)
(declare opts->vertx-options)
(declare build-verticle)
(declare build-disposable)

;; --- Public Api

(defn system
  "Creates a new vertx actor system instance."
  ([] (Vertx/vertx))
  ([opts]
   (let [^VertxOptions opts (opts->vertx-options opts)]
     (Vertx/vertx opts))))

(defn verticle
  [options]
  (reify Supplier
    (get [_] (build-verticle options))))

(defn deploy!
  ([vsm supplier] (deploy! vsm supplier nil))
  ([vsm supplier opts]
   (let [d (p/deferred)
         o (opts->deployment-options opts)]
     (.deployVerticle ^Vertx vsm
                      ^Supplier supplier
                      ^DeploymentOptions o
                      ^Handler (vu/deferred->handler d))
     (p/then' d (fn [id] (build-disposable vsm id))))))

(defn undeploy!
  [vsm id]
  (let [d (p/deferred)]
    (.undeploy ^Vertx vsm
               ^String id
               ^Handler (vu/deferred->handler d))
    d))

;; --- Impl

(defn- build-verticle
  [{:keys [on-start on-stop on-error]
    :or {on-error (constantly nil)
         on-stop (constantly nil)}}]
  (let [vsm (volatile! nil)
        ctx (volatile! nil)
        lst (volatile! nil)]
    (reify Verticle
      (init [_ instance context]
        (vreset! vsm instance)
        (vreset! ctx context))
      (getVertx [_] @vsm)
      (^void start [_ ^Future o]
       (-> (p/do! (on-start @ctx))
           (p/handle (fn [state error]
                       (if error
                         (do
                           (.fail o error)
                           (on-error @ctx error))
                         (do
                           (when (map? state)
                             (vswap! lst merge state))
                           (.complete o)))))))
      (^void stop [_ ^Future o]
       (p/handle (p/do! (on-stop @ctx @lst))
                 (fn [_ err]
                   (if err
                     (do (on-error err)
                         (.fail o err))
                     (.complete o))))))))

(defn- build-disposable
  [vsm id]
  (reify
    clojure.lang.IFn
    (invoke [_] (undeploy! vsm id))

    java.io.Closeable
    (close [_]
      @(undeploy! vsm id))))

(defn- opts->deployment-options
  [{:keys [instances worker?]}]
  (let [opts (DeploymentOptions.)]
    (when instances (.setInstances opts (int instances)))
    (when worker? (.setWorker opts worker?))
    opts))

(defn- opts->vertx-options
  [{:keys [threads]}]
  (let [opts (VertxOptions.)]
    (when threads (.setEventLoopPoolSize opts (int threads)))
    opts))



