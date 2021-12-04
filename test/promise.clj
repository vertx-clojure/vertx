(ns promise
  (:use [vertx.promise]))

;; less test require here, for the reason code base on io.vertx.core.Promise
(defn fmap-test "fmap will convert the data, it should keep the error"
  []
  (let [l [1 2 3 4]
        p-l (resolved l)
        ;; a type error
        wrong (fmap p-l #(reduce (fn [a b] (> a b))  0 %1))
        ;; not wrong
        right (fmap p-l #(apply + %1))]
    (assert (= (.result right) (apply + l)) "fmap should convert data")
    (assert (-> wrong (success?) (not)) "fmap should keep wrong error")
    (println "fmap-test pass")))
(fmap-test)

(defn test-join
  "test of join"
  []
  (let [l [1 2 3 4 5]
        pl (map resolved l)
        j  (join pl)
        p-sum (fmap j #(reduce (fn [s i] (+ i s)) 0 %1))
        r (.join (vertx.promise/->completeStage p-sum))]
    (assert (= r (apply + l))
            (str "join give a wrong result should: " (apply + l) " but " r)))
  (println "join-test pass"))

(test-join)
