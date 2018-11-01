(ns tvm-clj.jna.base
  (:require [tech.jna :refer [checknil] :as jna]
            [tech.datatype.jna :as dtype-jna]
            [tech.datatype :as dtype]
            [tvm-clj.jna.library-paths :as jna-lib-paths]
            [tvm-clj.bindings.definitions :as definitions]
            [tvm-clj.bindings.protocols :refer [->tvm-value ->tvm ->node
                                                device-type device-id byte-offset
                                                base-ptr] :as bindings-proto]
            [clojure.set :as c-set]
            [tech.resource :as resource])
  (:import [com.sun.jna Native NativeLibrary Pointer Function Platform]
           [com.sun.jna.ptr PointerByReference IntByReference LongByReference]
           [tvm_clj.tvm DLPack$DLContext DLPack$DLTensor DLPack$DLDataType
            DLPack$DLManagedTensor]))


(defmacro make-tvm-jna-fn
  "TVM functions are very regular so the mapping to them can exploit this.
Argpair is of type [symbol type-coersion]."
  [fn-name docstring rettype & argpairs]
  `(jna/def-jna-fn jna-lib-paths/tvm-library-name ~fn-name ~docstring ~rettype ~@argpairs))


(defn keyword->tvm-datatype
  [kwd]
  (definitions/keyword->tvm-datatype kwd))


(defn tvm-datatype->keyword-nothrow
  [tvm-datatype]
  (definitions/tvm-datatype->keyword-nothrow tvm-datatype))


(defn tvm-datatype->keyword
  [tvm-datatype]
  (definitions/tvm-datatype->keyword tvm-datatype))



(defn datatype->dl-type-code
  [datatype]
  (-> (get definitions/datatype->dl-type-code-map datatype)
      keyword->tvm-datatype))

(defn dl-datatype->map
  [^DLPack$DLDataType dtype]
  {:tvm-datatype (tvm-datatype->keyword (.code dtype))
   :bits (.bits dtype)
   :lanes (.lanes dtype)})


(defn dl-datatype->datatype
  [^DLPack$DLDataType dtype]
  (if-let [retval (->> (dl-datatype->map dtype)
                       (get definitions/dl-dtype-map->datatype-map))]
    retval
    (throw (ex-info "Unrecognized datatype"
                    {:dl-datatype->map dtype}))))


(defn datatype->dl-datatype
  [datatype & [dtype-retval]]
  (if-let [retval (get (c-set/map-invert definitions/dl-dtype-map->datatype-map) datatype)]
    (let [^DLPack$DLDataType dtype-retval (or dtype-retval (DLPack$DLDataType.))]
      (set! (.code dtype-retval) (long (keyword->tvm-datatype (:tvm-datatype retval))))
      (set! (.lanes dtype-retval) (long (:lanes retval)))
      (set! (.bits dtype-retval) (long (:bits retval)))
      dtype-retval)
    (throw (ex-info "Failed to find datatype" {:datatype datatype}))))



(defn int-ptr
  ^IntByReference [item]
  (jna/ensure-type IntByReference item))


(defn ptr-ptr
  ^PointerByReference [item]
  (jna/ensure-ptr-ptr item))

(defn long-ptr
  ^LongByReference [item]
  (jna/ensure-type LongByReference item))

(defn ->long-ptr
  [item]
  (if (instance? Pointer item)
    item
    (-> (dtype-jna/make-typed-pointer :int64 item)
        dtype-jna/->ptr-backing-store)))


(defn device-type->int
  [item]
  (let [item (if (satisfies? bindings-proto/PTVMDeviceType item)
               (device-type item)
               item)]
    (if (keyword? item)
      (definitions/device-type->device-type-int item)
      (int item))))

(defn device-id->int
  [item]
  (-> (if (satisfies? bindings-proto/PTVMDeviceId item)
        (device-id item)
        item)
      int))


(make-tvm-jna-fn TVMGetLastError
                 "Get last tvm error as byte ptr"
                 Pointer)

(defn get-last-error
  []
  (-> (TVMGetLastError)
      (jna/variable-byte-ptr->string)))


(def ^:dynamic fn-name "")


(defmacro check-call
  [& body]
  `(let [ret# (int (do ~@body))]
     (when-not (= 0 ret#)
       (let [byte-string# (get-last-error)]
         (throw (ex-info (format "Error during TVM call: %s" byte-string#)
                         {:error-string byte-string#
                          :fn-name fn-name}))))))




(make-tvm-jna-fn TVMFuncListGlobalNames
                 "List the global names"
                 Integer
                 [num-fns int-ptr]
                 [fn-names ptr-ptr])


(def global-function-names
  (memoize
   (fn []
     (let [int-data (IntByReference.)
           fn-names (PointerByReference.)]
       (check-call (TVMFuncListGlobalNames int-data fn-names))
       (->> (jna/char-ptr-ptr->string-vec (.getValue int-data)
                                          (.getValue fn-names))
            sort
            vec)))))


(make-tvm-jna-fn TVMFuncGetGlobal
                 "Get a global function ptr"
                 Integer
                 [fn-name str]
                 [fn-ptr ptr-ptr])


(defn name->global-function
  [fn-name]
  (let [retval (PointerByReference.)
        _ (check-call (TVMFuncGetGlobal fn-name retval))
        addr (.getValue retval)]
    (when (= 0 (Pointer/nativeValue addr))
      (throw (ex-info "Failed to find global function"
                      {:fn-name fn-name})))
    addr))


(make-tvm-jna-fn TVMFuncCall
                 "Call a tvm function"
                 Integer
                 [fn-handle checknil]
                 [arg_values checknil]
                 [type_codes checknil]
                 [num_args int]
                 [ret_val long-ptr]
                 [ret_type_code int-ptr])


(defn arg-list->tvm-args
 [args]
  (let [num-args (count args)
        arg-vals (dtype-jna/make-typed-pointer :int64 num-args)
        arg-types (dtype-jna/make-typed-pointer :int32 num-args)]
    (->> args
         (map-indexed (fn [idx arg]
                        (let [[long-val dtype] (->tvm-value arg)]
                          (dtype/set-value! arg-vals idx long-val)
                          (dtype/set-value! arg-types idx (keyword->tvm-datatype dtype)))))
         dorun)
    [arg-vals arg-types num-args]))



(defmulti tvm-value->jvm
  "Attempts to coerce the tvm value into the jvm.  Failures
result in a returned map container a value for the key:
:tvm->jvm-failure

This is in order to ensure that, for instance, deserialization of a node's fields
  allows for a sane recovery mechanism and doesn't lose those field values."
  (fn [long-val val-type-kwd]
    val-type-kwd))

(defmethod tvm-value->jvm :default
  [long-val val-type-kwd]
  (println (format "Failed to map value type %s" val-type-kwd))
  [long-val val-type-kwd])

(defmethod tvm-value->jvm :int
  [long-val val-type-kwd]
  long-val)

(defmethod tvm-value->jvm :uint
  [long-val val-type-kwd]
  long-val)

(defmethod tvm-value->jvm :float
  [long-val val-type-kwd]
  (Double/longBitsToDouble long-val))

(defmethod tvm-value->jvm :string
  [long-val val-type-kwd]
  (jna/variable-byte-ptr->string (Pointer. long-val)))

(defmethod tvm-value->jvm :null
  [long-val val-type-kwd]
  nil)


(defn call-function
  [tvm-fn & args]
  (let [fn-ret-val
        (resource/with-resource-context
          (let [retval (LongByReference.)
                rettype (IntByReference.)
                [tvm-args arg-types n-args] (arg-list->tvm-args args)]
            (check-call
             (TVMFuncCall tvm-fn
                          tvm-args arg-types n-args
                          retval rettype))
            [(.getValue retval) (tvm-datatype->keyword-nothrow (.getValue rettype))]))]
    (apply tvm-value->jvm fn-ret-val)))


(defn global-function
  [fn-name & args]
  (let [fn-data (name->global-function fn-name)]
    (with-bindings {#'fn-name fn-name}
      (apply call-function fn-data args))))
