;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.util
  (:require [promesa.core :as p])
  (:import io.vertx.core.Vertx
           io.vertx.core.Handler
           io.vertx.core.AsyncResult
           java.util.function.Supplier))

(defn fn->supplier
  [f]
  (reify Supplier
    (get [_] (f))))

(defn deferred->handler
  [d]
  (reify Handler
    (handle [_ ar]
      (if (.failed ar)
        (p/reject! d (.cause ar))
        (p/resolve! d (.result ar))))))

