;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.sql.pgsql
  (:require
   [promesa.core :as p])
  (:import
   io.vertx.core.Vertx
   io.vertx.core.Handler
   io.vertx.core.AsyncResult
   io.vertx.core.buffer.Buffer
   io.vertx.pgclient.PgPool
   io.vertx.sqlclient.impl.ArrayTuple
   io.vertx.sqlclient.RowSet
   io.vertx.sqlclient.PoolOptions))

(declare impl-execute)
(declare impl-query)
(declare impl-handler)
(declare impl-transact)
(declare seqable->tuple)

;; --- Public Api

(defn vertx?
  [v]
  (instance? Vertx v))

(defn pool?
  [v]
  (instance? PgPool v))

(defn bytes->buffer
  [data]
  (Buffer/buffer ^bytes data))

(defn pool
  ([uri] (pool uri {}))
  ([uri {:keys [system max-size] :or {max-size 6}}]
   (let [^PoolOptions poptions (PoolOptions.)]
     (when max-size (.setMaxSize poptions max-size))
     (if (vertx? system)
       (PgPool/pool ^Vertx system ^String uri poptions)
       (PgPool/pool ^String uri poptions)))))

(defn query
  ([conn sqlv] (query conn sqlv {}))
  ([conn sqlv opts]
   (cond
     (vector? sqlv)
     (impl-query conn (first sqlv) (rest sqlv) opts)

     (string? sqlv)
     (impl-query conn sqlv nil opts)

     :else
     (throw (ex-info "Invalid arguments" {:sqlv sqlv})))))

(defn query-one
  [& args]
  (p/map first (apply query args)))

(defn row->map
  [row]
  (reduce (fn [acc index]
            (let [cname (.getColumnName row index)]
              (assoc acc cname (.getValue row index))))
          {}
          (range (.size row))))

(defmacro atomic
  [csym & body]
  `(let [f# (fn [c#] (let [~csym c#] ~@body))]
     (impl-transact ~csym f#)))

;; --- Implementation

(defn- seqable->tuple
  [v]
  (let [res (ArrayTuple. (count v))]
    (run! #(.addValue res %) v)
    res))

(defn- impl-handler
  [resolve reject]
  (reify Handler
    (handle [_ ar]
      (if (.failed ar)
        (reject (.cause ar))
        (resolve (.result ar))))))

(defn- impl-execute
  [conn sql params]
  (if (seq params)
    (p/create #(.preparedQuery conn sql (seqable->tuple params) (impl-handler %1 %2)))
    (p/create #(.query conn sql (impl-handler %1 %2)))))

(defn row->map
  [row]
  (reduce (fn [acc index]
            (let [cname (.getColumnName row index)]
              (assoc acc cname (.getValue row index))))
          {}
          (range (.size row))))

(defn- impl-query
  [pool sql params {:keys [transform-fn] :as opts}]
  (->> (impl-execute pool sql params)
       (p/map (fn [rows]
                (if (fn? transform-fn)
                  (sequence (map transform-fn) rows)
                  (sequence (map vec) rows))))))

(defn impl-transact
  [pool f]
  (->> (p/create #(.getConnection pool (impl-handler %1 %2)))
       (p/map (fn [conn]
                (let [tx (.begin conn)]
                  (-> (p/do! (f conn))
                      (p/catch (fn [e]
                                 (.rollback tx)
                                 (.close conn)
                                 (p/rejected e)))
                      (p/then (fn [v]
                                (->> (p/create #(.commit tx (impl-handler %1 %2)))
                                     (p/map (fn [_]
                                              (.close conn)
                                              v)))))))))))

