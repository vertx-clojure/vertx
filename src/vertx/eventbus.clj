;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.eventbus
  (:require [promesa.core :as p]
            [vertx.util :as vu])
  (:import io.vertx.core.Vertx
           io.vertx.core.Handler
           io.vertx.core.eventbus.Message
           io.vertx.core.eventbus.MessageConsumer
           io.vertx.core.eventbus.DeliveryOptions
           io.vertx.core.eventbus.EventBus
           java.util.function.Supplier))

(declare get-eventbus)

;; --- Public Api

(defn consume!
  [vsm topic f]
  (let [^EventBus bus (get-eventbus vsm)
        ^MessageConsumer cons (.consumer bus ^String topic)]
    (.handler cons (reify Handler
                     (handle [_ msg]
                       (.pause cons)
                       (-> (p/do! (f msg))
                           (p/handle (fn [res err]
                                       (.resume cons)
                                       (.reply msg (or res err))))))))
    cons))

(defn publish!
  [vsm topic message]
  (let [bus (get-eventbus vsm)]
    (.publish ^EventBus bus
              ^String topic
              ^Object message)
    nil))

(defn send!
  [vsm topic message]
  (let [bus (get-eventbus vsm)]
    (.send ^EventBus bus
           ^String topic
           ^Object message)
    nil))

(defn request!
  [vsm topic message]
  (let [bus (get-eventbus vsm)
        d (p/deferred)]
    (.request ^EventBus bus
              ^String topic
              ^Object message
              ^Handler (vu/deferred->handler d))
    d))

;; TODO: add opts

(defn reply!
  [^Message sender ^Object message]
  (.reply sender message))

;; --- Impl

(defn- get-eventbus
  [o]
  (cond
    (instance? Vertx o) (.eventBus ^Vertx o)
    (instance? EventBus o) o
    :else (throw (ex-info "unexpected argument" {}))))

