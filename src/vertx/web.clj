;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Copyright (c) 2019-2021.
;;
;;  This Source Code Form is subject to the terms of the Mozilla Public
;;  License, v. 2.0. If a copy of the MPL was not distributed with this
;;  file, You can obtain one at http://mozilla.org/MPL/2.0/.
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c)  2021. Fish Coin <coincoinv@0day.im>
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns vertx.web
  "High level api for http servers."
  (:require
    [clojure.tools.logging :as log]
    [clojure.spec.alpha :as s]
    [promesa.core :as p]
    [sieppari.core :as sp]
    [reitit.core :as rt]
    [vertx.http :as vh]
    [vertx.promise :as vp]
    [vertx.util :as vu])
  (:import
    clojure.lang.IPersistentMap
    clojure.lang.Keyword
    io.vertx.core.Future
    io.vertx.core.Handler
    io.vertx.core.Vertx
    io.vertx.core.buffer.Buffer
    io.vertx.core.http.Cookie
    io.vertx.core.http.HttpMethod
    io.vertx.core.http.HttpServer
    io.vertx.core.http.HttpServerOptions
    io.vertx.core.http.HttpServerRequest
    io.vertx.core.http.HttpServerResponse
    io.vertx.core.http.ServerWebSocket
    io.vertx.ext.web.Route
    io.vertx.ext.web.Router
    io.vertx.ext.web.RoutingContext
    io.vertx.ext.web.handler.BodyHandler
    io.vertx.ext.web.handler.LoggerHandler
    io.vertx.ext.web.handler.ResponseTimeHandler
    io.vertx.ext.web.handler.StaticHandler))

;; --- Public Api

(s/def ::wrap-handler
       (s/or :fn  fn?
             :vec (s/every fn? :kind vector?)))

(defn- ->request
  [^RoutingContext routing-context]
  (let [^HttpServerRequest request   (.request ^RoutingContext routing-context)
        ^HttpServerResponse response (.response ^RoutingContext routing-context)
        ^Vertx system                (.vertx routing-context)]
    {:body            (.getBody routing-context)
     :path            (.path request)
     :param           (.params request)
     :query           (.query request)
     :headers         (vh/->headers (.headers request))
     :method          (-> request .method .name)
     :request         request
     :response        response
     :vertx-context   (.getContext system)
     :next            (fn [] (.next routing-context))
     :fail            (fn ([e] (.fail routing-context e))
                        ([status e] (.fail routing-context (int status) e)))
     :routing-context routing-context}))

(defn- cached-router
  "return a cached router that is dynamic generated, with symbol handler"
  [vsm handlers]
  (vu/fn->handler
    (fn [raw-req]
      (try
        (let [ctx       (.getOrCreateContext vsm)
              map       (.contextData ctx)
              key       "__cached.router"
              _init     (when (not (.contains map key))
                          (.putIfAbsent map key (java.util.concurrent.ConcurrentHashMap.)))
              cache     (.get map key)
              [r time]  (or (.get cache handlers) [nil 0])]
          (if (and r (> time (- (System/currentTimeMillis) 200)))
            (do
              (println "cached at" time handlers "->" r)
              (.handle r raw-req))
            (let [r      (Router/router vsm)
                  reg-fn (fn [_ f] (if (fn? f) (f r) ((eval f) r)))]
              ;; reg handlers
              (reduce reg-fn r handlers)
              (.put cache handlers [r (System/currentTimeMillis)])
              (println "load " handlers "->" r)
              (.handle r raw-req))))
        (catch Exception e
          (println e))))))

(defn handler
  "Wraps a user defined funcion based handler into a vertx-web aware
  handler (with support for multipart uploads.

  If the handler is a vector, the sieppari intercerptos engine will be used
  to resolve the execution of the interceptors + handler."
  [vsm & handlers]
  (let [^Vertx vsm     (vu/resolve-system vsm)
        static-router (reduce #(if %1 %1 (fn? %2)) nil handlers)
        ^Router router (Router/router vsm)]
    (if static-router
      (reduce #(%2 %1) router handlers)
      (cached-router vsm handlers))))

(defn assets
  ([path] (assets path {}))
  ([path {:keys [root] :or {root "public"} :as options}]
   (fn [^Router router]
     (let [^Route route     (.route router path)
           ^Handler handler (doto (StaticHandler/create)
                                  (.setWebRoot root)
                                  (.setDirectoryListing true))]
       (.handler route handler)
       router))))

(defn- default-handler
  [ctx]
  (if (::match ctx)
    {:status 405}
    {:status 404}))

(defn- default-on-error
  [err req]
  (log/error err)
  {:status 500
   :body   "Internal server error!\n"})

(defn- run-chain
  [ctx chain handler]
  (let [d (p/deferred)]
    (sp/execute (conj chain handler) ctx #(p/resolve! d %) #(p/reject! d %))
    d))

(defn- router-handler
  [router {:keys [path method] :as ctx}]
  (let [{:keys [data path-params] :as match} (rt/match-by-path router path)
        handler-fn                           (or (get data method)
                                                 (get data :all)
                                                 default-handler)
        interceptors                         (get data :interceptors)
        ctx                                  (assoc ctx ::match match :path-params path-params)]
    (if (empty? interceptors)
      (handler-fn ctx)
      (run-chain ctx interceptors handler-fn))))

(defn router
  ([routes] (router routes {}))
  ([routes
    {:keys [delete-uploads?
            upload-dir
            on-error
            log-requests?
            time-response?]
     :or   {delete-uploads? true
            upload-dir      "/tmp/vertx.uploads"
            on-error        default-on-error
            log-requests?   false
            time-response?  true}
     :as   options}]
   (let [rtr (rt/router routes options)
         f   #(router-handler rtr %)]
     (fn [^Router router]
       (let [^Route route (.route router)]
         (when time-response? (.handler route (ResponseTimeHandler/create)))
         (when log-requests? (.handler route (LoggerHandler/create)))

         (doto route
               (.failureHandler
                 (reify
                  Handler
                  (handle [_ rc]
                          (let [err (.failure ^RoutingContext rc)
                                req (.get ^RoutingContext rc "vertx$clj$req")]
                            (-> (p/do! (on-error err req))
                                (vh/-handle-response req))))))

               (.handler
                 (doto (BodyHandler/create true)
                       (.setDeleteUploadedFilesOnEnd delete-uploads?)
                       (.setUploadsDirectory upload-dir)))

               (.handler
                 (reify
                  Handler
                  (handle [_ rc]
                          (let [req (->request rc)
                                efn (fn [err]
                                      (.put ^RoutingContext rc "vertx$clj$req" req)
                                      (.fail ^RoutingContext rc err))]
                            (try
                              (-> (vh/-handle-response (f req) req)
                                  (p/catch' efn))
                              (catch Exception err
                                (efn err)))))))))
       router))))

(defn headers
  "take the header"
  [route-context]
  (:headers (->request route-context)))

(defn server-request
  "exctract vert.x server request for custom use"
  [^io.vertx.ext.web.RoutingContext route-context]
  (.request route-context))

(defn path
  "take the path of request"
  [^io.vertx.ext.web.RoutingContext route-context]
  (:path (->request route-context)))

(defn query
  "take the query of request"
  [route-context]
  (:query (->request route-context)))

(defn ->multi-map
  "convert the clojure persistent map into MultiMap"
  [map_]
  (let [multi-map (io.vertx.core.MultiMap/caseInsensitiveMultiMap)]
    (reduce
     (fn [m [k v]]
       (.add m
             (if (or (symbol? k) (keyword? k))
               (.substring ^java.lang.String (str k) 1)
               (str k))
             v))
     multi-map
     map_)
    multi-map))

(defn- METHOD-MAP
  [name]
  (if (= name :ALL)
    (HttpMethod/values)
    (let [method  (.toUpperCase (.substring (str name) 1))]
      (HttpMethod/valueOf method))))

(defn- list'? [x]
  (or (list? x) (vector? x)))

(defn- build-register-route
  "build the route register argument"
  ([path handler]
   (build-register-route [:GET :POST :PUT :DELETE]
                         path
                         handler))
  ([method path handler]
   (if (list'? handler)
     (build-register-route method path (reverse (rest (reverse handler))) (first (reverse handler)))
     (build-register-route method path [] handler)))
  ([method path handler respond]
   ;; actually build the all argument
   (let [method  (if (list'? method) method [method])
         path    (if (list'? path) path [path])
         handler (if (list'? handler) handler [handler])]
     {:method  method
      :uri     path
      :handler handler
      :respond respond
      :options {:delete-uploads? true
                :upload-dir      "/tmp/vertx.uploads"
                :on-error        default-on-error
                :log-requests?   false
                :time-response?  true}})))

(defn ->map [multi-map]
  "dangeroups api that may cost all memory"
  (if multi-map
    (reduce
      (fn [m [k i]]
        (.put m (keyword k) i)
        m)
      (new java.util.HashMap)
      multi-map)

    {}))

;; prepare the function
(declare add-route)

(defn- set-default-handler
  "set the body handler, the response-time handler, log-request handler, upload handler"
  [route     {:keys [delete-uploads?
                     upload-dir
                     log-requests?
                     time-response?]
              :or   {delete-uploads? true
                     upload-dir      "/tmp/vertx.uploads"
                     log-requests?   false
                     time-response?  true}
              :as   options}]
  (when time-response? (.handler route (ResponseTimeHandler/create)))
  (when log-requests? (.handler route (LoggerHandler/create)))
  (.handler route
            (doto (BodyHandler/create true)
                  (.setDeleteUploadedFilesOnEnd delete-uploads?)
                  (.setUploadsDirectory upload-dir))))

(defn- cache
  "because the eval take time so use the cache to speed up"
  [ctx sy]
  (let [record   (or (.get ctx ":last-record") {})
        [f time] (.get record sy)
        now      (System/currentTimeMillis)]
    (if (or (not f) (> (- now 200) time))
      (let [f-eval  (eval sy)
            now     (System/currentTimeMillis)
            _update (.put ctx ":last-record" (assoc record sy [f-eval now]))]
        f-eval)
      f)))

;; real action to register the route
(defn- register-route'
  [router'
   {:keys [name
           order
           blocking
           regex
           uri
           method
           routes
           router
           handler
           respond
           custom
           options]
    :as   config}]
  (let [^Route r (.route router')]
    ;; set the default handler
    (set-default-handler r options)
    ;; set the path first
    (when uri
      (reduce
        (fn [_ uri]
          (if regex
            (.pathRegex r uri)
            (.path r uri)))
        r uri))

    ;; set the sub-router
    (when router
      (reduce
       (fn [_ sub-route] (.subRouter r sub-route)) r router))

    ;; set the method
    (if method
      ;; set all method
      (reduce (fn [_ name] (.method r (METHOD-MAP name))) r method)
      ;; else
      (.method r io.vertx.core.http.HttpMethod/GET))

    ;; add handler for the further use
    (when handler
      (let [f-reduce (fn [_ f-handler]
                       (let [f   (fn [rtx]
                                   (let [sys       (.vertx ^RoutingContext rtx)
                                         ctx       (.getOrCreateContext sys)
                                         request   (->request rtx)
                                         f-handler (if (fn? f-handler) f-handler (cache ctx f-handler))]
                                     (f-handler request)))
                             fun (vu/fn->handler f)]
                         (if blocking
                           (.blockingHandler r fun)
                           (.handler r fun))))]
        (reduce f-reduce r handler)))

    ;; set the respond
    (when respond
      (.respond r
                (reify
                 java.util.function.Function
                 (apply [_ rtx]
                        (let [request (->request rtx)
                              sys     (.vertx rtx)
                              ctx     (.getOrCreateContext sys)
                              respond (if (fn? respond) respond (cache ctx respond))
                              res     (respond request)]
                          (if (instance? java.util.concurrent.CompletionStage res)
                            (vp/from res)
                            res))))))

    ;; custom the route if neccesary
    (when custom
      (custom r))
    ;; return the router for reduce use
    (when name
      (.name r))
    (when order
      (.order (int r)))
    (if routes
      (add-route router' routes)
      router')))

(defn- register-route
  "register the route with argument, see the source for further detail"
  [^Router router' list-or-map]
  (if (list'? list-or-map)
    ;; if is list build it into map and recur invoke
    (register-route router' (apply build-register-route list-or-map))
    (register-route' router' list-or-map)))

(defn add-route
  "add route, a little pheonix-like api.
  accept a list [[METHOD \"path\" HANDLER]], HANDLER -> context -> param -> Future
  this is handle by multi Route dealer that will accept like [METHOD:Keyword PATH:String HANDLER:Fn] and each of it could be list like [[METHOD]:[Keybord] [PATH]:[String] [HANDLER]:Fn]. WARN: when HANDLER is LIST, only the last one should return Future others it just handle the context
  args could be the Map {:routes [[same as out-side]] :name \"route-name\" :order 1 :uri path:String :blocking true :regex true :router [sub-route] :handler [Fn] :respond Fn}, for example (add-route router {:uri [\"/api/request\"] :method [:GET] :router [sub-router] :blocking false :handler [JsonContextHandler] :respond response-to-api :custom custom-Fn}), custom-Fn should be able to deal with route"
  [router route-list]
  (reduce register-route
          router
          route-list))



(defn build-route
  "create a Fn for vertx.web/handle use, deps on add-route"
  [route-config]
  (fn [router]
    ;; set the default handler for the router
    (add-route router route-config)))

(defn handle-error
  "create an error-handler, invoke like (error-handler status, (fn [error routing-context] ...)"
  [status error-handle]
  (fn [router]
    (.errorHandler ^Router router (int status)
                   (vu/fn->handler
                     (fn [routing-context]
                       (error-handle
                        (.failure routing-context)
                        routing-context))))

    router))
