(ns compressible-map.compressible-val-test
  (:require [compressible-map.compressible-val :refer :all]
            [clojure.test :refer :all]))

(deftest test-Compressible-states
  (let [v [:a :b :c]
        decompressed (->CompressibleVal v nil into)
        compressed (compress decompressed)]
    (is (not (compressed? decompressed)))
    (is (compressed? compressed))
    (is (not (compressed? (decompress compressed))))
    ;; idempotency 
    (is (compressed? (compress compressed)))
    (is (not (compressed? (decompress decompressed))))
    ))

;; Value of CompressibleVal should remain the same regardless of
;; compression state
(deftest test-deref
  (let [v [:a :b :c]
        decompressed (->CompressibleVal v nil into)
        compressed (compress decompressed)]
    (is (= v @decompressed))
    (is (= v @compressed))
    (is (= v @(decompress compressed)))    
    )
  (let [v [:a :b :c :d]
        decompressed (->CompressibleVal [:a :b] [:c :d] into)
        compressed (compress decompressed)]
    (is (= v @decompressed))
    (is (= v @compressed))
    (is (= v @(decompress compressed)))
    )
  )

(deftest test-update
  (let [cv (->CompressibleVal [:a :b] [:f :g] (comp sort into))]
    (is (= [:a :f :g] @(update-front cv subvec 0 1)))
    (is (= [:a :b :f :g] @(update-front cv concat nil)))
    (is (= [:a :b :f :g :x] @(update-front cv conj :x)))
    (is (= [:a :b :c :f :g] @(update-front cv conj :c)))
    (is (= [:a :b :c :f :g] @(update-front (compress cv) conj :c)))
    (is (= [:a :b :c :f :g] @(compress (update-front cv conj :c))))    
    ))


