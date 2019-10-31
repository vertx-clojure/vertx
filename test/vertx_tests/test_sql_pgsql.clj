(ns vertx-tests.test-sql-pgsql
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [vertx.sql.pgsql :as pg]))

(t/deftest create-connection-pool
  (with-open [pool (pg/pool "postgresql://test@localhost/test")]
    (t/is (pg/pool? pool))))

(t/deftest simple-query
  (with-open [pool (pg/pool "postgresql://test@localhost/test")]
    @(pg/query pool "create temp table test (id bigserial, value int)")
    (let [res @(pg/query-one pool ["insert into test (value) values ($1) returning *" 333])]
      (t/is (= [1 333] res)))))
