(ns actor
  (:require [vertx.core :as vc]))

(def s (vc/system))

(let [i (fn [_] {:a 1})
      h (fn [msg {:keys [a]} res error]
          (println "a -> " a " msg -> " (:body msg))
          (res {:merge {:a (+ a 1)}
                :reply (str "Ok: " a)}))
      a (vc/actor [[:a h]] {:on-start i})]
  (vc/deploy! s a))

(p/then (vertx.eventbus/request! s :a "Yeah!")
        (fn [data]
          (println "Data -> " data)))

(.close s)

(let [s (p/deferred)
      t (p/then s (fn [d] (println "D -> " d)))]
  (p/resolve! s "Here"))
