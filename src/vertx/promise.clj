;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Copyright (c) 2021.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.
;   Copyright (c)  2021. Fish Coin <coincoinv@0day.im>
;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(ns vertx.promise
  (:import io.vertx.core.Future
           io.vertx.core.Promise))

;; to provide a clj-style promise

(defn deferred
  "create a empty promise for further use"
  []
  (Promise/promise))

(defn promise?
  "check if promise"
  [p]
  (instance? Promise p))

(defn ->future
  "get a promise future (useless at normal situation)"
  [p]
  (.future p))

(defn resolve!
  "complete promise with a data"
  ([p] (.complete p)
       p)
  ([p data]
   (.complete p data)
   p))

(defn resolve'
  "try complete promise"
  ([p] (.tryComplete p))
  ([p d] (.tryComplete p d)))

(defn resolved
  "create a completed future"
  ([] (Future/succeededFuture))
  ([data] (Future/succeededFuture data)))

(defn failed
  "create a failed future"
  [error]
  (Future/failedFuture error))

(defn fail!
  "send fail to promise"
  [p error]
  (.fail p error)
  p)

(defn fail'
  "try-fail promise"
  ([p e] (.tryFail p e)))

(defn handle!
  "let promise handle a future(or promise)"
  [p asyn-result]
  (.handle p asyn-result)
  p)

(defn result'
  "extract result, null if fail or not completed"
  [fu]
  (.result fu))
(defn cause' [fu]
  "get error cause, null if succeeded or not completed"
  (.cause fu))

(defn fail?
  [fu]
  (.failed fu))
(defn success?
  [fu]
  (.succeeded fu))
(defn complete? [fu]
  (.isComplete fu))

(defn handle
  "(handle future (fn [result error]))"
  [fu success-or-error-fn]
  (.onComplete fu
               (reify
                 io.vertx.core.Handler
                 (handle [_ r]
                   (success-or-error-fn (.result r)
                                        (.cause r))))))

(defn handle'
  "(handle future (fn [result error]))"
  [fu success-or-error-fn]
  (.onComplete fu
               (reify
                 io.vertx.core.Handler
                 (handle [_ r]
                   (success-or-error-fn
                    (.succeeded r)
                    (.result r)
                    (.cause r))))))
(defn then
  "set the next action of future if succeeded"
  [fu fn-on-success]
  (handle' fu
           (fn [suc t _]
             (when suc
               (fn-on-success t)))))
(defn error-then
  "handle error"
  [fu fn-on-error]
  (handle' fu
           (fn [suc _ e]
             (when (not suc)
               (fn-on-error e)))))

(defn compose
  "handle data or error and convert into new future.
	 the fn should return future
	(compose future fn-succeeded)
	(compose future fn-succeeded fn-failed)"
  ([fu f-succes] (compose fu f-succes failed))
  ([fu f-succes f-error]
   (let [stack (RuntimeException.)]
     (try
       (.compose
        fu
        (reify
          java.util.function.Function
          (apply [_ t]
            (let [r (f-succes t)]
              (when-not (instance? Future r)
                (.error (io.vertx.core.logging.LoggerFactory/getLogger Future)
                        "Fail to Convert the Future At" stack))
              r)))
        (reify
          java.util.function.Function
          (apply [_ e]
            (let [r
                  (f-error
                   (if (instance? String e) (io.vertx.core.impl.NoStackTraceThrowable. e) e))]
              (when-not (instance? Future r)
                (.error (io.vertx.core.logging.LoggerFactory/getLogger Future)
                        "Fail to Convert the Future At" stack))
              r))))

       (catch Exception e
         (failed e))))))

(def chain compose)

(defn fmap
  "fmap, execute the f if the future is success"
  [fu f]
  (compose fu
           (fn [x]
             (resolved (f x)))))

(defn recover
  "recover, recover the failed future into a good one"
  [fu r]
  (compose fu resolved
           (fn [e]
             (resolved (r e)))))

(defn join
  "join, make combine/join a list of promise into one,[Promise ?] -> Promise[?]"
  [promise-list]
  (reduce
   #(chain %2 (fn [data] (fmap %1 (fn [list] (conj list data)))))
   (resolved [])
   promise-list))

(defn ->completeStage
  [fu]
  (.toCompletionStage fu))

(defn fromCompleteStage
  [stage]
  (Future/fromCompletionStage stage))
(defn from
  "convert stage or (future (fn [p] (resolve p true))) let fn decide create future"
  [fn-promise]
  (if (instance? java.util.concurrent.CompletionStage fn-promise)
    (fromCompleteStage fn-promise)
    (Future/future
      (reify
        io.vertx.core.Handler
        (handle [_ p]
          (fn-promise p))))))
