;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.core
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [vertx.eventbus :as vxe]
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
(declare build-actor)
(declare build-disposable)

;; --- Protocols

(definterface IVerticleFactory)

;; --- Public Api

(s/def :vertx.system/threads pos?)
(s/def ::system-options
  (s/keys :opt-un [:vertx.system/threads]))

(defn system
  "Creates a new vertx actor system instance."
  ([] (Vertx/vertx))
  ([options]
   (s/assert ::system-options options)
   (let [^VertxOptions opts (opts->vertx-options options)
         ^Vertx vsm (Vertx/vertx opts)]
     (vxe/configure! vsm opts)
     vsm)))

(s/def :vertx.verticle/on-start fn?)
(s/def :vertx.verticle/on-stop fn?)
(s/def :vertx.verticle/on-error fn?)
(s/def ::verticle-options
  (s/keys :req-un [:vertx.verticle/on-start]
          :opt-un [:vertx.verticle/on-stop
                   :vertx.verticle/on-error]))

(defn verticle
  "Creates a verticle instance (factory)."
  [options]
  (s/assert ::verticle-options options)
  (reify
    IVerticleFactory
    Supplier
    (get [_] (build-verticle options))))

(defn verticle?
  "Return `true` if `v` is instance of `IVerticleFactory`."
  [v]
  (instance? IVerticleFactory v))

(s/def :vertx.actor/on-message fn?)
(s/def :vertx.actor/on-start :vertx.verticle/on-start)
(s/def :vertx.actor/on-stop  :vertx.verticle/on-stop)
(s/def :vertx.actor/on-error :vertx.verticle/on-error)
(s/def ::actor-options
  (s/keys :req-un [:vertx.actor/on-message]
          :opt-un [:vertx.actor/on-start
                   :vertx.actor/on-error
                   :vertx.actor/on-stop]))

(defn actor
  "A shortcut for create a verticle instance (factory) that consumes a
  specific topic."
  [topic options]
  (s/assert string? topic)
  (s/assert ::actor-options options)
  (reify
    IVerticleFactory
    Supplier
    (get [_] (build-actor topic options))))

(defn deploy!
  "Deploy a verticle."
  ([vsm supplier] (deploy! vsm supplier nil))
  ([vsm supplier opts]
   (s/assert verticle? supplier)
   (let [d (p/deferred)
         o (opts->deployment-options opts)]
     (.deployVerticle ^Vertx vsm
                      ^Supplier supplier
                      ^DeploymentOptions o
                      ^Handler (vu/deferred->handler d))
     (p/then' d (fn [id] (build-disposable vsm id))))))

(defn undeploy!
  "Undeploy the verticle, this function should be rarelly used because
  the easiest way to undeplo is executin the callable returned by
  `deploy!` function."
  [vsm id]
  (let [d (p/deferred)]
    (.undeploy ^Vertx (vu/resolve-system vsm)
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

(defn- build-actor
  [topic {:keys [on-message on-error on-stop on-start]
          :or {on-error (constantly nil)
               on-start (constantly {})
               on-stop (constantly nil)}}]
  (letfn [(-on-start [ctx]
            (let [state (on-start ctx)
                  consumer (vxe/consumer ctx topic on-message)]
              (assoc state ::consumer consumer)))]
    (build-verticle {:on-error on-error
                     :on-stop on-stop
                     :on-start -on-start})))

(defn- build-disposable
  [vsm id]
  (reify
    clojure.lang.IDeref
    (deref [_] id)

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



