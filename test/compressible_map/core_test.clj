(ns compressible-map.core-test
  (:require [compressible-map.core :refer :all]
            [compressible-map.compressible-val :as cv]
            [clojure.test :refer :all]))

;;
;; The main idea of these tests is to construct a set of equivalent
;; CompressibleMaps which vary in their internal state.
;; We then test that we support all the map protocols correctly in all states,
;;

(defn little-map-plain [] {:a 0 :b 1 :c 3})

(defn little-map-variants []
  [(conj (->CompressibleMap) (little-map-plain))])

;; (deftest test-equality
;;   (for [x (little-map-variants), y (little-map-variants)]
;;     (is (= x y))))

(deftest test-as-map
  (let [m (conj (->CompressibleMap) (little-map-plain))]
    (is (= (little-map-plain) (as-map m)))))

(deftest test-container-protocols
  (let [m (conj (->CompressibleMap) (little-map-plain))]
  ;(for [m (little-map-variants)]
    (do
      (is (= 3 (count m)))
      (is (= 4 (count (assoc m :d 5))))
      (is (= (assoc (little-map-plain) :d 5) (as-map (assoc m :d 5))))
      (is (= (dissoc (little-map-plain) :a) (as-map (dissoc m :a))))
      (is (contains? m :c))
      (is (not (contains? m :x)))
      (is (= 1 (get m :b)))
      (is (= nil (get m :x)))
      (is (= 1 (:b m)))
      (is (= nil (:x m)))
      (is (= [:b 1] (find m :b)))
      (is (= nil (find m :x)))
      (is (= #{:a :b :c} (set (keys m))))
      (is (= #{0 1 3} (set (vals m))))
      (is (nil? (seq (->CompressibleMap))))
      ;; seq's are equal if they contain the same map entries
      (is (= (set (seq (little-map-plain))) (set (seq m))))
      (is (= (seq {}) (seq (empty m))))
      (is (= (set (cons [:x 4] (little-map-plain))) (set (cons [:x 4] m))))
      )))
;; Test some of the Java interfaces
(deftest test-interfaces
  (let [m (conj (->CompressibleMap) (little-map-plain))]
    (is (.hasNext (.iterator m)))
    ))

;; Test that "write" operations wrap new values
;; and return type CompressibleMap
(deftest test-preserve-type
  (let [compressible-map-class (Class/forName "compressible_map.core.CompressibleMap")
        cm-type? (fn [o] (= compressible-map-class (type o)))
        m (conj (->CompressibleMap) (little-map-plain))]
    (is (cm-type? (assoc m :x 4)))
    (is (cm-type? (dissoc m :a)))
    (is (cm-type? (empty m)))
    (is (cm-type? (conj m [:x 4])))
    (is (cm-type? (merge m {:x 4})))
    (is (cm-type? (reduce #(conj %1 %2) m [[:x 4]])))
    (is (cm-type? (compress m :a)))
    (is (cm-type? (decompress m :a)))
    ))

(deftest test-nested-map-ctor
  (let [cm (compressible-nested-map)]
    (is (= nested-merge (.mergefn cm)))))

;;
;; Functionally test the management of compression and decompression
;;


(deftest test-compress
  
  (let [cm (-> (->CompressibleMap)
               (conj  (little-map-plain))
               (compress :c))
        ]
    (is (cv/compressed? (:c (raw-map cm))))
    (is (not (cv/compressed? (:a (raw-map cm)))))
    (is (= 3 (:c cm)))                  ; should automatically decompress/deref the val
    ))

(deftest test-decompress
  
  (let [cm (-> (->CompressibleMap)
               (conj  (little-map-plain))
               (compress :c))
        ]
    (is (not (cv/compressed? (:c (raw-map (decompress cm :c))))))
    (is (= 3 (:c cm)))                  ; should automatically deref the val

    ;; Make sure we can assoc onto a decompressed CompressedVal
    (let [cm (assoc cm :c 999)]
      (is (not (cv/compressed? (:c (raw-map cm)))))
      (is (= 999 (:c cm))))

    ))
;;
;; Test nested-map functionality, similar to tuple-db
;;
(let [m1 {:a 1, :b 2, :c {:c0 0, :c1 1, :c2 2}}
      m2 {:x 1, :b 7, :z 3 :c {:c0 5 :c4 6}}
      ;; CompressibleMap with values decompressed
      cm (-> (compressible-nested-map)
             (assoc :m1 m1)
             (assoc :m2 m2))
      ;; with values compressed
      ccm (reduce #(compress %1 %2) cm (keys cm))
      ]

  (deftest test-nested-merge
    (is (= {:a 1
            :b 2
            :c {:c0 0
                :c1 1
                :c2 2
                :c4 6}
            :x 1
            :z 3}
           (nested-merge m1 m2))))
  
  (deftest test-nested-map
    (is (= 2 (get-in cm [:m1 :c :c2])))
    (is (= 2 (get-in ccm [:m1 :c :c2])))
    (let [cm2 (assoc-in-compressed cm [:m1 :c :c2] 999)
          ccm2 (assoc-in-compressed ccm [:m1 :c :c2] 999)]
      (is (cv/compressed? (:m1 (raw-map ccm2))))
      (is (= 999 (get-in cm2 [:m1 :c :c2])))
      (is (= 999 (get-in ccm2 [:m1 :c :c2])))
      (is (= (as-map cm2) (as-map ccm2))))
    ;; test assoc-in with a non-matching key path
    (let [cm2 (assoc-in-compressed cm [:m3 :x :y] 7777)
          ccm2 (assoc-in-compressed ccm [:m3 :x :y] 7777)]
      (is (= 7777 (get-in cm2 [:m3 :x :y])))
      (is (= 7777 (get-in ccm2 [:m3 :x :y])))
      (is (= (as-map cm2) (as-map ccm2))))
    )

  )                                     ; let m1, m2
