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
   [reitit.core :as r]
   [vertx.web :as vw]
   [sieppari.context :as spx]
   [sieppari.core :as sp])
  (:import
   clojure.lang.Keyword
   clojure.lang.MapEntry
   java.util.Map
   java.util.Map$Entry
   io.vertx.core.Vertx
   io.vertx.core.Handler
   io.vertx.core.Future
   io.vertx.core.http.Cookie
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.ext.web.FileUpload
   io.vertx.ext.web.RoutingContext))

;; --- Cookies

(def cookies
  (letfn [(parse-cookie [^Cookie item]
            [(.getName item) (.getValue item)])
          (encode-cookie [name data]
            (cond-> (Cookie/cookie ^String name ^String (:value data))
              (:http-only data) (.setHttpOnly true)
              (:domain data) (.setDomain (:domain data))
              (:path data) (.setPath (:path data))
              (:secure data) (.setSecure true)))
          (add-cookie [^HttpServerResponse res [name data]]
            (.addCookie res (encode-cookie name data)))
          (enter [data]
            (let [^HttpServerRequest req (get-in data [:request ::vw/request])
                  cookies (into {} (map parse-cookie) (vals (.cookieMap req)))]
              (update data :request assoc :cookies cookies)))
          (leave [data]
            (let [cookies (get-in data [:response :cookies])
                  res (get-in data [:request ::vw/response])]
              (when (map? cookies)
                (run! (partial add-cookie res) cookies))
              data))]
    {:enter enter
     :leave leave}))

;; --- Headers

(def lowercase-keys-t
  (map (fn [^Map$Entry entry]
         (MapEntry. (.toLowerCase (.getKey entry)) (.getValue entry)))))

(def headers
  (letfn [(parse [req]
            (let [^HttpServerRequest request (::vw/request req)]
              (into {} lowercase-keys-t (.headers request))))

          (enter [data]
            (update data :request assoc :headers (parse (:request data))))

          (leave [data]
            (let [^HttpServerResponse res (get-in data [:request ::vw/response])
                  headers (get-in data [:response :headers])]
              (run! (fn [[key value]]
                      (.putHeader ^HttpServerResponse res
                                  ^String (name key)
                                  ^String (str value)))
                    headers)
              data))]
    {:enter enter
     :leave leave}))

;; --- Params

(defn- parse-param-entry
  [acc ^Map$Entry item]
  (let [key (keyword (.toLowerCase (.getKey item)))
        prv (get acc key ::default)]
    (cond
      (= prv ::default)
      (assoc! acc key (.getValue item))

      (vector? prv)
      (assoc! acc key (conj prv (.getValue item)))

      :else
      (assoc! acc key [prv (.getValue item)]))))

(defn- parse-params
  [req]
  (let [request (::vw/request req)]
    (persistent!
     (reduce parse-param-entry
             (transient {})
             (.params ^HttpServerResponse request)))))

(def params
  {:enter (fn [data]
            (let [params (parse-params (:request data))]
              (update data :request assoc :params params)))})

;; --- Uploads

(def uploads
  {:enter (fn [data]
            (let [context (get-in data [:request ::vw/routing-context])
                  uploads (reduce (fn [acc ^FileUpload upload]
                                    (assoc acc
                                           (keyword (.name upload))
                                           {:type :uploaded-file
                                            :mtype (.contentType upload)
                                            :path (.uploadedFileName upload)
                                            :name (.fileName upload)
                                            :size (.size upload)}))
                                  (transient {})
                                  (.fileUploads ^RoutingContext context))]
              (update data :request assoc :uploads (persistent! uploads))))})

;; --- CORS

(s/def ::origin string?)
(s/def ::allow-credentials boolean?)
(s/def ::allow-methods (s/every keyword? :kind set?))
(s/def ::allow-headers (s/every keyword? :kind set?))
(s/def ::expose-headers (s/every keyword? :kind set?))
(s/def ::max-age number?)

(s/def ::cors-opts
  (s/keys :req-un [::origin]
          :opt-un [::allow-headers
                   ::allow-methods
                   ::expose-headers
                   ::max-age]))

(defn cors
  [opts]
  (s/assert ::cors-opts opts)
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
                       "access-control-allow-methods" "GET, OPTIONS, HEAD"}

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
              (if (preflight? ctx)
                (spx/terminate (assoc data ::preflight true))
                data)))

          (leave [data]
            (let [headers (get-headers (:request data))]
              (if (::preflight data)
                (assoc data :response {:status 204 :headers headers})
                (update-in data [:response :headers] merge headers))))]

    {:enter enter
     :leave leave}))
