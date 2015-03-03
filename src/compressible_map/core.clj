(ns compressible-map.core
  "CompressibleMap is a persistent map which compresses its values.

  Values are wrapped in CompressibleVal.  See event-log.compressible-val for the semantics of this type.
  Values are assumed to be collections, implementing IPersistentCollection, although any value can be wrapped,
  given a suitable merge function.
  The `empty` method is used to instantiate a new empty collection of the same type, as needed.
  The provided `mergefn` is used to merge the front and back buffer of the CompressibleVal.
"
  
  (:require [compressible-map.compressible-val :as cv :refer [->CompressibleVal]])
  (:import [compressible_map.compressible_val CompressibleVal]
           [java.lang UnsupportedOperationException Iterable]
           [java.util Map Map$Entry]
           [clojure.lang Counted Seqable ILookup IPersistentCollection
            IPersistentMap IPersistentVector Associative MapEntry SeqIterator]))

(defn- wrap-value
  "Wrap value in CompressibleVal"
  [v mergefn] (->CompressibleVal v nil mergefn))

(defn- deref-entry
  "Deref the CompressibleVal in a Map.Entry"
  [[k v]] (MapEntry. k @v))

(deftype CompressibleMap [m mergefn compress-queue]

  Object
  (toString [_] (.toString m))

  ;; marker interface
  clojure.lang.MapEquivalence

  clojure.lang.Counted
  (count [_]
    (count m))

  ;; Wrap added values in CompressibleVal
  clojure.lang.IPersistentMap
  ;; TODO: avoid new instance if we already contain the given entry
  (assoc [_ k v]
    (CompressibleMap. (.assoc m k (wrap-value v mergefn))  mergefn compress-queue))
  (assocEx [_ k v]
    (CompressibleMap. (.assocEx m k (wrap-value v mergefn))  mergefn compress-queue))
  (without [_ k]
    (CompressibleMap. (.without m k) mergefn compress-queue))

  clojure.lang.Associative
  (containsKey [_ k] (.containsKey m k))
  ;; entryAt is the main value "read" access point
  (entryAt [_ k]
    (when (contains? m k)
      ;; Deref b/c we assume all our values are wrapped as CompressibleVal
      (deref-entry (find m k))))

  clojure.lang.IPersistentCollection
  ;; cons is used to implment conj
  ;; We only accept pairs or Map.Entry as arg
  (cons [this o]
    (if (instance? java.util.Map$Entry o)
      (.assoc this (.getKey o) (.getValue o))

      ;; else 
      (if (instance? IPersistentVector o)
        (if (= 2 (count o))
          (.assoc this (first o) (second o))
          ;; else
          (throw (IllegalArgumentException. "Vector arg to map conj must be a pair")))
        
        ;; else treat as a seq of pairs
        (reduce conj this o)
        )))

  (empty [_]
    (CompressibleMap. (empty m) mergefn (empty compress-queue)))
  
  (equiv [_ o]
    ;; Clojure persistent maps reject equivalence with CompressibleMap
    ;; because of type inequality, so for symmetry, we implement type-and-data equivalence
    ;; as well.
    (and (instance? CompressibleMap o)
         (= m (:m o)))
    )

  clojure.lang.Seqable
  (seq [_]
    (when-let [entry-seq (seq m)]
      (map deref-entry entry-seq)))

  ;; Needed to support reduce
  java.lang.Iterable
  (iterator [this] (SeqIterator. (seq this)))
  
  ;; TODO: java.util.Map

  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k not-found]
    ;; Not using .entryAt to avoid creation of intermediate MapEntry
    (if (contains? m k)
      @(get m k)
      not-found))
  )                                     ; CompressibleMap

(defn as-map
  "Return CompresibleMap as a decompressed, persistent map."
  [m] (into {} (seq m)))


(defn default-mergefn
  "Default merge fn -- non-nil front val will override back val"
  [front-val back-val] (if (nil? front-val)
                         back-val
                         front-val))

(defn nested-merge
  "Recursively merge maps, with front-val winning conflicts.
  val-merge-fn -- Function to merge two values with the same key path.  Default front-val wins."
  ([front-val back-val] (nested-merge (fn [f b] f) front-val back-val))
  ([val-merge-fn front-val back-val]
     (if (or (map? front-val) (map? back-val))
       (merge-with (partial nested-merge val-merge-fn) front-val back-val)
       (val-merge-fn front-val back-val))))

;; Functions needing access to the raw CompressibleVal need to work
;; directly with the map wrapped in CompressibleMap, as the access functions
;; implemented above all wrap and unwrap the values in CompressibleVal, making
;; it impossible to access the underlying CompressibleVal.

(defn raw-map
  "Use to access underlying map when tests need to inspect a CompressedVal without the
  automatic decompression/deref that is applied by CompressedMap"
  [cm] (.m cm))

(defn- assoc-direct
  "assoc the given value without wrapping it in a CompressibleVal"
  [cm k v] (CompressibleMap. (assoc (raw-map cm) k v) (.mergefn cm) (.compress-queue cm)))

(defn- update-direct
  "update the entry associated with the given key by applying the given function, without
  wrapping the result in a CompressibleVal"
  [cm k f & args]
  (if-let [cv ^CompressibleVal (get (raw-map cm) k)]
    (assoc-direct cm k (apply f cv args))
    ;; else, key not found, so just return the unchanged map
    cm
    ))

(defn compress
  "Compress a val in a CompressibleMap.  If key is not present, return the unaltered map"
  ([m k] (update-direct m k cv/compress)))

(defn decompress
  "Decompress a val in a CompressibleMap.  If key is not present, return the unaltered map"
  ([m k] (update-direct m k cv/decompress)))

;;
;; Write operations that operate on compressed values
;;
(defn assoc-in-compressed
  "Associates a value in a nested associative structure, where ks is a
  sequence of keys and v is the new value and returns a new nested structure.
  Works like regular assoc-in, except that the top level CompressedValue will not be
  decompressed/dereffed.  This means that the update will go to the front buffer of the
  CompressedVal"
  [m [k & ks] v]
  
  (if ks
    (let [cv (or (get (raw-map m) k)
                 (wrap-value {} (.mergefn m)))
          updated-cv (cv/update-front cv assoc-in ks v)]
      (assoc-direct m k updated-cv))
    ;; Else, only one top-level key, so just replace the value
    (assoc m k v)))

;;
;; factory function
;;

(defn ->CompressibleMap
  ([] (->CompressibleMap default-mergefn))
  ([mergefn] (CompressibleMap. {} mergefn (atom [])))
  )

(defn compressible-nested-map
  "Construct a CompressibleMap using a map merge function in which elements of the front
  buffer override matching elements of the back buffer"
  [] (->CompressibleMap nested-merge))


;; For pretty-printing in the REPL
(defmethod print-method CompressibleMap [o w] (print-simple o w))

