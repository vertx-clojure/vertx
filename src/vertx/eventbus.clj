;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.eventbus
  (:require [promesa.core :as p]
            [msgpack.core :refer [pack unpack]]
            [msgpack.macros :refer [extend-msgpack]]
            [vertx.util :as vu])
  (:import io.vertx.core.Vertx
           io.vertx.core.buffer.Buffer
           io.vertx.core.json.JsonObject
           io.vertx.core.json.JsonArray
           io.vertx.core.Handler
           io.vertx.core.Context
           io.vertx.core.eventbus.Message
           io.vertx.core.eventbus.MessageConsumer
           io.vertx.core.eventbus.DeliveryOptions
           io.vertx.core.eventbus.EventBus
           io.vertx.core.eventbus.MessageCodec))

(declare opts->delivery-opts)
(declare resolve-eventbus)
(declare build-message-codec)
(declare build-message)

;; --- Public Api

(defn consumer
  "f :: Vertx -> Msg -> ReplyMsg, and it will resume the msg handler"
  [vsm topic f]
  (let [^EventBus bus             (resolve-eventbus vsm)
        ^MessageConsumer consumer (.consumer bus ^String (str topic))]
    (.handler consumer
              (reify
                Handler
                (handle [_ msg]
                  (.pause consumer)
                  (-> (p/do! (f vsm (build-message msg)))
                      (p/handle
                       (fn [res err]
                         (.resume consumer)
                         (.reply msg (or res err)
                                 (opts->delivery-opts {}))))))))
    consumer))

(defn publish!
  ([vsm topic msg] (publish! vsm topic msg {}))
  ([vsm topic msg opts]
   (let [bus  (resolve-eventbus vsm)
         opts (opts->delivery-opts opts)]
     (.publish ^EventBus bus
               ^String (str topic)
               ^Object msg
               ^DeliveryOptions opts)
     nil)))

(defn send!
  ([vsm topic msg] (send! vsm topic msg {}))
  ([vsm topic msg opts]
   (let [bus  (resolve-eventbus vsm)
         opts (opts->delivery-opts opts)]
     (.send ^EventBus bus
            ^String (str topic)
            ^Object msg
            ^DeliveryOptions opts)
     nil)))

(defn request!
  ([vsm topic msg] (request! vsm topic msg {}))
  ([vsm topic msg opts]
   (let [bus  (resolve-eventbus vsm)
         opts (opts->delivery-opts opts)
         d    (p/deferred)]
     (.request ^EventBus bus
               ^String (str topic)
               ^Object msg
               ^DeliveryOptions opts
               ^Handler (vu/deferred->handler d))
     (p/then' d build-message))))

(defn configure!
  [vsm opts]
  (let [^EventBus bus (resolve-eventbus vsm)]
    (.registerCodec bus (build-message-codec))))

(defrecord Msg [body])

(defn message?
  [v]
  (instance? Msg v))

;; --- Impl

(defn- resolve-eventbus
  [o]
  (cond
    (instance? Vertx o)    (.eventBus ^Vertx o)
    (instance? Context o)  (resolve-eventbus (.owner ^Context o))
    (instance? EventBus o) o
    :else                  (throw (ex-info "unexpected argument" {}))))

(extend-msgpack Buffer
                12
                [b] (pack (.getBytes ^Buffer b))
                [bytes] (Buffer/buffer (unpack bytes)))

(extend-msgpack JsonObject
                10
                [o] (pack (.getMap ^JsonObject o))
                [bytes] (JsonObject. (unpack bytes)))
(extend-msgpack JsonArray
                11
                [o] (pack (.getList ^JsonArray o))
                [bytes] (JsonArray. (unpack bytes)))

(extend-msgpack java.util.Map
                12
                [map']
                (let [out (java.io.ByteArrayOutputStream.)]
                  (.writeBytes out (pack (.size map')))
                  (reduce #(doto out
                             (.writeBytes (pack (.getKey %2)))
                             (.writeBytes (pack (.getValue %2))))
                          out map')
                  (.toByteArray out))
                [bytes] (let [map' (java.util.HashMap.)
                              in (java.io.ByteArrayInputStream. bytes)
                              size (unpack in)]
                          (loop [i 0]
                            (when (< i size)
                              (.put map' (unpack in) (unpack in))
                              (recur (+ i 1))))
                          map'))

(extend-msgpack java.util.List
                13
                [list']
                (let [out (java.io.ByteArrayOutputStream.)]
                  (.writeBytes out (pack (.size list')))
                  (reduce #(.writeBytes out (pack %2))
                          out list')
                  (.toByteArray out))
                [bytes] (let [list' (java.util.ArrayList.)
                              in (java.io.ByteArrayInputStream. bytes)
                              size (unpack in)]
                          (loop [i 0]
                            (when (< i size)
                              (.add list' (unpack in))
                              (recur (+ i 1))))
                          list'))

(extend-msgpack java.util.Set
                14
                [list']
                (let [out (java.io.ByteArrayOutputStream.)]
                  (.writeBytes out (pack (.size list')))
                  (reduce #(.writeBytes out (pack %2))
                          out list')
                  (.toByteArray out))
                [bytes] (let [list' (java.util.HashSet.)
                              in (java.io.ByteArrayInputStream. bytes)
                              size (unpack in)]
                          (loop [i 0]
                            (when (< i size)
                              (.add list' (unpack in))
                              (recur (+ i 1))))
                          list'))

(defn- build-message-codec
  []
  (reify
    MessageCodec
    (encodeToWire [_ buffer data]
      (try
        (.appendBytes ^io.vertx.core.buffer.Buffer buffer (pack data))
        (catch Exception e
          (.appendString ^io.vertx.core.buffer.Buffer buffer (io.vertx.core.json.Json/encode data)))))
    (decodeFromWire [_ pos buffer]
      (try
        (unpack (.getBytes ^io.vertx.core.buffer.Buffer buffer pos (.length buffer)))
        (catch Exception e
          (io.vertx.core.json.Json/decodeValue
           (.getBuffer ^io.vertx.core.buffer.Buffer buffer pos (.length buffer))))))
    (transform [_ data] data)
    (name [_] "clj:msgpack")
    (^byte systemCodecID [_] (byte -1))))

(defn- build-message
  [^Message msg]
  (let [metadata {::reply-to (.replyAddress msg)
                  ::send?    (.isSend msg)
                  ::address  (.address msg)}
        body     (.body msg)]
    (Msg. body metadata nil)))

(defn opts->delivery-opts
  [{:keys [codec local?] :as o}]
  (let [^DeliveryOptions opts (DeliveryOptions.)
        toStr                 (fn [x] (if (keyword? x) (.substring (str x) 1) x))]
    (.setCodecName opts (or codec "clj:msgpack"))
    (when local? (.setLocalOnly opts true))
    (reduce
     (fn [o [i v]]
       (when (not (and (= i :codec) (= i :local?)))
         (.addHeader ^DeliveryOptions o (toStr i) (str v))))
     opts o)))
