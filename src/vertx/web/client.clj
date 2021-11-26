;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2021 Fish Coin <coincoinv@0day.im>

(ns vertx.web.client
  "High level http client."
  (:refer-clojure :exclude [get])
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [sieppari.core :as sp]
   [reitit.core :as rt]
   [vertx.http :as vh]
   [vertx.util :as vu])
  (:import
   clojure.lang.IPersistentMap
   clojure.lang.Keyword
   java.net.URL
   io.vertx.core.Future
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpMethod
   io.vertx.core.http.RequestOptions
   io.vertx.core.net.ProxyOptions
   io.vertx.core.net.ProxyType
   io.vertx.core.MultiMap
   io.vertx.core.json.JsonObject
   io.vertx.core.json.JsonArray
   io.vertx.ext.web.client.HttpRequest
   io.vertx.ext.web.client.HttpResponse
   io.vertx.ext.web.client.WebClientSession
   io.vertx.ext.web.client.WebClient))

(defn create
  ([vsm] (create vsm {}))
  ([vsm opts]
   (let [^Vertx system (vu/resolve-system vsm)]
     (WebClient/create system))))

(defn session
  [client]
  (WebClientSession/create client))

(defn to-method [method]
  (let [m (HttpMethod/valueOf (if (keyword? method) (-> (str method) (.substring 1)) (str method)))]
    (assert (.contains (HttpMethod/values) m) (str "method[" method "]must be http method"))
    m
    ))

(defn- to-proxy-opt [opt]
  (let [option (ProxyOptions.)]
    (.setHost option (:host opt))
    (.setPort option (.intValue (:port opt)))
    (.setUsername option (:username opt))
    (.setPassword option (:password opt))
    (.setType option (ProxyType/valueOf (str (:type opt))))
    option ))

(defn- to-request-option [opt]
  (let [option (RequestOptions.)]
    (.setHost option (:host opt))
    (.setPort option (int (:port opt)))
    (.setSsl  option (:ssl opt))
    (.setURI  option (or (:uri opt) "/"))
    (when (:timeout opt)
      (.setTimeout option (.intValue (:timeout opt))) )
    (when (:followRedirects opt)
      (.setFollowRedirects option (:followRedirects opt)) )
    ;; add headers
    (when (:headers opt)
      (reduce (fn [_ [name value]]
                (if (or (symbol? name) (keyword? name))
                (.putHeader option (.substring (str name) 1) (str value))
                (.putHeader option (str name) (str value)) ))
              {}
              (:headers opt) ))
    (when (:proxy opt)
      (.setProxyOptions option
                        (to-proxy-opt (:proxy opt)) ))
    option ))

(defn- is-ssl [url-struct]
  (let* [p   (.getProtocol url-struct)
         len (.length p)
         lc  (.charAt p (- len 1))
         c   (Character/toUpperCase lc)]
    (= c \S)))

(defn- toJson
  "convert the data into Json for the further use"
  [data]
  ;; check if clojure struct, if clojure struct be, convert it
  ;; if none clojure struct simply
  (cond
    (string? data) data
    (char? data) data
    (number? data) (BigDecimal. (str data))
    (keyword? data) (str data)
    (symbol? data) (str data)
    ;; if vector, it should convert into JsonArray
    (vector? data) (reduce (fn [array x] (.add array (toJson x))
                             array)
                           (JsonArray.)
                           data)

    (list? data)   (reduce (fn [array x] (.add array (toJson x))
                             array)
                           (JsonArray.)
                           data)

    (set? data)    (reduce (fn [array x] (.add array (toJson x))
                             array)
                           (JsonArray.)
                           data)

    ;; if map, it should convert into a JsonObject and add other into it
    (map? data)    (reduce (fn [js [x y]]
                             (if (or (symbol? x) (keyword? x))
                               (.put js (.substring (str x) 1) (toJson y))
                               (.put js (str x) (toJson y))
                               )
                             js)
                           (JsonObject.)
                           data)

    true (JsonObject/mapFrom data)
))

(defn- toForm
  "convert the immutable-map into Form map, if value is a list, it would be convert into MultiMap"
  [data]
  (let [form (MultiMap/caseInsensitiveMultiMap)]
    (reduce (fn [_ [i v]]
              (cond
                (instance? Iterable data) (.add form i v)
                true (.add form i v) ))
            {}
            data)
  form))

(defn- toBuffer
  "conver the data into buffer, the byte"
  [data]
  (.toBuffer (toJson data)))

;; use the immutable-map as request query
(defn- toQuery
  "use immutable-map as request query"
  [req header-map]
  (reduce (fn [_ [i v]] (.addQueryParam req
                                        (if (or (symbol? i) (keyword? i))
                                          (.substring (str i) 1)
                                          (str i))
                                        (str v))) {} header-map)
  ;; must return the req
  req)

;; request api for the webclient
(defn request
  "request:: WebClient -> ('GET|'POST|'PUT|'DELETE -> URL(String) ) | {:uri string :ssl bool :port int :method atom :host string :headers {} :custom (fn [HttpRequest])} -> (:form | :json | :buffer | :query -> Future)"
  ([cli method url]
   (request cli
            (let* [url-struct (URL. url)
                   ssl (is-ssl url-struct)
                   port (.getPort url-struct)
                   r_port (if (= port -1)
                            (if ssl
                              443
                              80)
                            port)]
                  {:host (.getHost url-struct)
                   :port r_port
                   :method method
                   :uri   (.getFile url-struct)
                   :ssl  ssl})))

  ([cli method host uri]
   (request cli {:host host
                 :port 80
                 :method method
                 :uri  uri
                 :ssl  false}))

  ([cli method host port uri]
   (request cli {:host host
                 :port port
                 :method method
                 :uri  uri
                 :ssl  false}))

  ;; real request, wrap the request-option into real one
  ([cli request-option]

   (let [m (to-method (:method request-option))
         o (to-request-option request-option)
         req (.request cli m o)
         d (p/deferred)]
     ;; custom the request if require
     ;; you can add header or auth for it, there is no clojure style support for now. sorry
     (when-let [custom (:custom request-option)]
         (assert (fn? custom) "option {:custom custom} must be fn [^Request request] or nil")
         ((:custom request-option) req))
     ;; wrap the response into future, the future api is made by origin author
     (letfn [(f-req [httpRequest]
                     ;; here we got a asyncResult. let's checkout if it real workout
                     ;; question why not use a self impl future? the promesa lib use a threadpool that work out of the vertx
                     (.onComplete httpRequest (vu/deferred->handler d))
                     (p/then d (fn [^HttpResponse res]
                                 {:body (.bodyAsBuffer res)
                                  :status (.statusCode res)
                                  :headers (vh/->headers (.headers res))})))]

       ;; convert the data transform
       (fn
         ([type data]
          (let [httpRequest (if data
                              (cond
                                (= type :query)  (.send (toQuery req data))
                                (= type :json)   (.sendJson req (toJson data))
                                (= type :form)   (.sendForm req (toForm data))
                                (= type :buffer) (.sendBuffer req (toBuffer data)))
                              (.send req))]
            (f-req httpRequest)))

         ;; send none data, just send the request
         ([] (f-req (.send req))))))))
