;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web.interceptors
  "High level api for http servers."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [promesa.core :as p]
   [sieppari.core :as sp]
   [sieppari.context :as spx]
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
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.ext.web.RoutingContext))

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

(defn cors-interceptor
  [opts]
  ;; TODO: validate opts
  (letfn [(preflight? [{:keys [method headers] :as ctx}]
            (and (= method :options)
                 (contains? headers "origin")
                 (contains? headers "access-control-request-method")))

          (normalize [data]
            (str/join ", " (map name data)))

          (allow-origin? [headers]
            (let [origin (:origin opts)
                  value (get headers "origin")]
              (cond
                (nil? value) value
                (= origin "*") origin
                (set? origin) (origin value)
                (= origin value) origin)))

          (get-headers [{:keys [headers] :as ctx}]
            (when-let [origin (allow-origin? headers)]
              (cond-> {"access-control-allow-origin" origin
                       "access-control-allow-methods" "GET, OPTIONS"}

                (:allow-methods opts)
                (assoc "access-control-allow-methods"
                       (-> (normalize (:allow-methods opts))
                           (str/upper-case)))

                (:allow-credentials opts)
                (assoc "access-control-allow-credentials" "true")

                (:expose-headers opts)
                (assoc "access-control-expose-headers"
                       (-> (normalize (:expose-headers opts))
                           (str/lower-case)))

                (:max-age opts)
                (assoc "access-control-max-age" (:max-age opts))

                (:allow-headers opts)
                (assoc "access-control-allow-headers"
                       (-> (normalize (:allow-headers opts))
                           (str/lower-case))))))

          (enter [data]
            (let [ctx (:request data)]
              (prn "cors:enter" "preflight?" (preflight? ctx))
              (if (preflight? ctx)
                (spx/terminate (assoc data ::preflight true))
                data)))

          (leave [data]
            (let [headers (get-headers (:request data))]
              (if (::preflight data)
                (assoc data :response {:status 200 :headers headers})
                (update-in data [:response :headers] merge headers))))]

    {:enter enter
     :leave leave}))