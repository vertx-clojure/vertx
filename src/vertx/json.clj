(ns vertx.json
  (:import [io.vertx.core.json JsonObject JsonArray Json]
           [io.vertx.core.buffer Buffer]))

(defn json [object]
  ;; convert it to json
  (cond
    (nil? object) nil
    (instance? java.util.Map object) (JsonObject. object)
    (or (seq? object)
        (list? object)
        (vector? object)) (JsonArray. object)
    (instance? Iterable object) (JsonArray. (java.util.ArrayList. object))
    (or (string? object) (instance? Buffer object)) (Json/decodeValue object)
    :else (JsonObject/mapFrom object)))

(defn json->map [^JsonObject json-object]
  (.getMap json-object))

(defn json->list [^JsonArray json-array]
  (.getList json-array))

(defn json->buffer [json]
  (.toBuffer json))

(defn json->obj [json class]
  (cond
    (string? json) (Json/decodeValue json class)
    (instance? JsonObject json) (.mapTo json class)
    :else (Json/decodeValue json)))