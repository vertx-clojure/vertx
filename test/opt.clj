
(ns vertx.core
  (:require [vertx.core :as vc]))

(def opt (opts->deployment-options {:instances 5 :config {:name "coincoinv"}}))

(def config (.getConfig opt))

(println "config -> " config)

(defn test []
  (assert (= (.getValue config ":name"  "coincoinv"))))

(ns opt)
(vertx.core/test)