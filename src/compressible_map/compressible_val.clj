(ns compressible-map.compressible-val
  "CompressibleVal is a low-level type used by CompressibleMap to double-buffer and compress a map value.
  CompressibleVal is dereferencible.  Dereferencing returns its decompressed, merged value.   
  
  mergefn is a binary function which is used to merge the front and back into a single value
  Front and back buffer are always merged before compression

  Values are assumed to be collections, implementing IPersistentCollection.
  The `empty` method is used to instantiate a new empty collection of the same type, as needed.

  Note that the dereferenced value is not equivalent to the value of the CompressibleVal itself, because the partitioning of  data into front and back buffer are part of the structure of the value.  Note also that merging, and by extension compression, can alter the value of both the CompressibleVal and the dereferenced value.  For instance:

    event-log.compressible-val> (def cv (CompressibleVal. [:a :b] [] into))
    #'event-log.compressible-val/cv

    event-log.compressible-val> cv
    decompressed: {front: [:a :b] back: []}

    event-log.compressible-val> (compress cv)
    compressed: {front: [] back: #<byte[] [B@1e4e3035>}

    event-log.compressible-val> @(compress cv)
    [:a :b]

    event-log.compressible-val> @(update-front cv conj :x)
    [:a :b :x]

    event-log.compressible-val> @(compress (update-front cv conj :x))
    [:a :b :x]

    event-log.compressible-val> @(update-front (compress cv) conj :x)
    [:x :a :b]

    ;; Compare to:

    event-log.compressible-val> (def cv (CompressibleVal. [:a :b] [] (comp sort into)))
    #'event-log.compressible-val/cv

    event-log.compressible-val> @(compress (update-front cv conj :x))
    (:a :b :x)

    event-log.compressible-val> @(update-front (compress cv) conj :x)
    (:a :b :x)
"

  (:require [taoensso.nippy :as nippy]
            [clojure.pprint :refer :all])
  (:import [clojure.lang IDeref IPersistentMap]))

;; used in byte-array? test
(def byte-array-type (Class/forName "[B"))

(defprotocol Compressible
  (compress [this]
    "Compress object if it is not already compressed")
  (decompress [this]
    "Decompress value if it is compressed")
  (compressed? [this]
    "True if this object is compressed")
  )

(defrecord CompressibleVal [front back mergefn]

  Compressible
  (compressed? [this]
    ;; We assume if type is byte array, then it is compressed
    (= (type back) byte-array-type))
  (compress [this]
    (if (compressed? this)
      this                              ; already compressed
      ;; else, merge and compress
      (let [new-back (nippy/freeze (mergefn front back))]
          (->CompressibleVal (empty front) new-back mergefn)))) 
  (decompress [this]
    (if (compressed? this)
      (->CompressibleVal front (nippy/thaw back) mergefn)
      this                              ; else, already decompressed
      ))

  IDeref
  (deref [this]
    (let [decompressed (decompress this)]
      (mergefn (:front decompressed) (:back decompressed))))
  )

(defn update-front
  "Returns the result of applying the function f to the front buffer."
  [^CompressibleVal v f & args]
  (CompressibleVal. (apply f (:front v) args) (:back v) (:mergefn v)))

(defmethod print-method CompressibleVal [o w]
  (if (compressed? o)
    (.write w "compressed: {")
    (.write w "decompressed: {"))
  (.write w "front: ")
  (print-method (:front o) w)
  (.write w " back: ")
  (print-method (:back o) w)
  (.write w "}"))


(defmethod simple-dispatch CompressibleVal [v]
  (print-method v *out*))
