(ns tvm-clj.api
  "Higher level API to build and compile tvm functions."
  (:require [tvm-clj.tvm-jna :refer [->node] :as bindings]
            [tvm-clj.jna.node :as jna-node]
            [tvm-clj.bindings.protocols :as bindings-proto]
            [tech.datatype :as dtype]
            [tech.resource :as resource]
            [clojure.set :as c-set]
            [clojure.string :as s])
  (:refer-clojure :exclude [range cast mod min max]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defmacro when-not-error
  [condition throw-clause]
  `(when-not (do ~condition)
     (throw ~throw-clause)))


(defn- ->dtype
  ^String [dtype-or-name]
  (cond
    (keyword? dtype-or-name)
    (name dtype-or-name)
    (string? dtype-or-name)
    dtype-or-name
    :else
    (throw (ex-info "Invalid datatype detected"
                    {:dtype dtype-or-name}))))

(def ^:dynamic *varname-prefix* "")

(defn safe-str
  [str-name]
  (let [str-name (if (keyword? str-name)
                   (name str-name)
                   (str str-name))]
    (s/replace (str *varname-prefix* str-name) "-" "_")))


(defn variable
  "Create a scalar variable.  Returns a node handle"
  [^String name & {:keys [dtype]
                   :or {dtype "int32"}}]
  (bindings/global-node-function "_Var"
                          (safe-str name)
                          (->dtype dtype)))


(defn placeholder
  "Create a user-supplied tensor variable"
  [shape name & {:keys [dtype]
                 :or {dtype "float32"}}]
  (let [shape (->> (if-not (instance? clojure.lang.Seqable shape)
                     [shape]
                     shape)
                   (mapv ->node))]
    (bindings/global-node-function "_Placeholder" shape (->dtype dtype) (safe-str name))))


(defn range
  "Create a range with defined start inclusive and end exclusive"
  [start end]
  (bindings/global-node-function "Range" start end))


(defn const
  "Convert an item to a const (immediate) value"
  [numeric-value & [dtype]]
  (let [dtype (->dtype (or dtype
                           (dtype/get-datatype numeric-value)))]
    (bindings/global-node-function "_const" numeric-value dtype)))


(defn static-cast
  "Cast an item from one datatype to another"
  [dtype expr-node]
  (bindings/global-node-function "make.Cast" (->dtype dtype) (->node expr-node)))


(defn cast
  "See static cast redone to allow usage in ->"
  [expr-node dtype]
  (static-cast dtype expr-node))


(def call-types
  "Possible call types from Halide/IR.h"
  {:extern 0 ;;< A call to an external C-ABI function, possibly with side-effects
   :extern-c-plus-plus 1 ;;< A call to an external C-ABI function, possibly with side-effects
   :pure-extern 2 ;;< A call to a guaranteed-side-effect-free external function
   :halide 3 ;;< A call to a Func
   :intrinsic 4  ;;< A possibly-side-effecty compiler intrinsic, which has special handling during codegen
   :pure-intrinsic 5 ;;< A side-effect-free version of the above.
   })

(defn ->call-type
  ^long [ctype]
  (cond
    (keyword? ctype)
    (if-let [retval (get call-types ctype)]
      retval
      (throw (ex-info "Failed to find call type"
                      {:call-type ctype})))
    (number? ctype)
    (long ctype)))


(def call-type-set (set (keys call-types)))


(defn call
  "Call a 'function', which is basically executing a statement.  For instance, getting a
  value from the tensor is calling a halide function with the tensor's generating-op and
  value index."
  [ret-dtype fn-name fn-args call-type function-ref value-index]
  (bindings/global-node-function "make.Call" (->dtype ret-dtype) fn-name fn-args
                          (->call-type call-type)
                          function-ref value-index))


(defn call-pure-intrin
  "Build expression by calling a pure intrinsic function.

    Intrinsics can be overloaded with multiple data types via
    the intrinsic translation rule.

    Parameters
    ----------
    dtype : str
        The data type of the result.

    func_name: str
        The intrinsic function name.

    args : list
        Positional arguments.

    Returns
    -------
    call : Expr
        The call expression.
    "
  [dtype func-name & args]
  (call dtype func-name (->node args) :pure-intrinsic nil 0))


(defn call-intrin
  "Build expression by calling an intrinsic function.

    Intrinsics can be overloaded with multiple data types via
    the intrinsic translation rule.

    Parameters
    ----------
    dtype : str
        The data type of the result.

    func_name: str
        The intrinsic function name.

    args : list
        Positional arguments.

    Returns
    -------
    call : Expr
        The call expression.
    "
  [dtype func-name & args]
  (call dtype func-name (->node args) :intrinsic nil 0))


(defn tget
  "Get an item from a tensor"
  [tensor indices]
  (let [indices (if (number? indices)
                  [indices]
                  indices)]
    (when-not-error (= (count (:shape tensor))
                       (count indices))
      (ex-info "Num indices must match tensor rank"
               {:tensor-range (count (:shape tensor))
                :index-count (count indices)}))
    (let [indices
          (mapv (fn [index-val]
                  (let [node-data (->node index-val)]
                    (cond
                      (= :iteration-variable (bindings/get-node-type node-data))
                      (:var node-data)
                      (bindings/is-expression-node? node-data)
                      node-data
                      :else
                      (throw (ex-info "Must be iteration variable or expression"
                                      {:node-type (bindings/get-node-type node-data)})))))
                indices)]
      (call (:dtype tensor) (get-in tensor [:op :name]) indices
            :halide (:op tensor) (:value_index tensor)))))


(defmethod jna-node/get-extended-node-value :tensor
  [node-handle item-key]
  (cond
    (or (number? item-key)
        (sequential? item-key))
    (tget node-handle item-key)
    (= item-key :axis) (:axis (:op node-handle))
    :else
    nil))


(defmacro def-bin-op
  "Define a binary operation"
  [op-name make-name]
  (let [lhs (symbol "lhs")
        rhs (symbol "rhs")]
    `(defn ~op-name
       [~lhs ~rhs]
       (bindings/global-node-function ~make-name ~lhs ~rhs))))


(defmacro def-op
  "Define a binary operation"
  [op-name make-name]
  (let [lhs (symbol "lhs")]
    `(defn ~op-name
       [~lhs]
       (bindings/global-node-function ~make-name ~lhs))))


(defmacro def-bin-intrin-op
  [op-name]
  (let [lhs (symbol "lhs")
        rhs (symbol "rhs")]
    `(defn ~op-name
       [~lhs ~rhs]
       (call-pure-intrin (dtype/get-datatype ~lhs)
                         ~(str op-name)
                         ~lhs
                         ~rhs))))

(defmacro def-intrin-op
  [op-name]
  (let [lhs (symbol "lhs")]
    `(defn ~op-name
       [~lhs]
       (call-pure-intrin (dtype/get-datatype ~lhs)
                         ~(str op-name)
                         ~lhs))))



(def-bin-op add "make.Add")
(def-bin-op sub "make.Sub")
(def-bin-op mod "make.Mod")
(def-bin-op mul "make.Mul")
(def-bin-op div "make.Div")
(def-bin-op eq "make.EQ")
(def-bin-op min "make.Min")
(def-bin-op max "make.Max")
(def-intrin-op exp)
(def-intrin-op tanh)
(def-intrin-op sigmoid)
(def-intrin-op log)
(def-intrin-op sqrt)
(def-intrin-op floor)
(def-intrin-op ceil)
(def-intrin-op trunc)
(def-op abs "make.abs")
(def-intrin-op round)
(def-bin-intrin-op power)
(def-intrin-op popcount)



(defn select
  "Select between two expressions based on a condition.  Thus works similar to the
clojure 'if' statement."
  [bool-stmt true-stmt false-stmt]
  (bindings/global-node-function "make.Select" bool-stmt true-stmt false-stmt))


(defn- get-for-type-idx
  ^long [for-type]
  (case for-type
    :serial 0
    :parallel 1
    :vectorize 2
    :unroll 3))


(defmacro tvm-let
  "Lets in tvm must be nested.  This leads to an exciting macro.
  Pairs must be of the form var val-expr.  Body is *not* an implicit
  do!!"
  [expr-pairs body]
  (->> expr-pairs
       (partition 2)
       reverse
       (reduce (fn [data [var-symbol expr]]
                 `(let [evaled-expr# ~expr
                        ~var-symbol (variable ~(safe-str var-symbol)
                                              :dtype (:dtype evaled-expr#))]
                    (bindings/g-fn "make.Let" ~var-symbol evaled-expr# ~data)))
               body)))


(def iteration-variable-types
  "Iteration variable types defined in tvm/include/Expr.h"
  {
   ;; /*!
   ;; * \brief Data parallel iteration.
   ;; *  This normally corresponds to axis of Tensor.
   ;; *  Allow all IterVar manipulations.
   ;; *
   ;; * \note This does not mean the loop
   ;; *  have to be executed in parallel fashion.
   ;; */
   :data-parallel 0
   ;; /*!
   ;; * \brief The IterVar itself is a thread-index
   ;; *  of a fixed thread launching group.
   ;; *  Note that this is already assumed to be parallelized.
   ;; *
   ;; *  Disallow: split/fuse/vectorize/parallel
   ;; */
   :thread-index 1
   ;; /*!
   ;; * \brief Communicative reduction.
   ;; *  Cannot be directly parallelized.
   ;; *
   ;; *  Disallow: parallel/vectorize
   ;; */
   :communicative-reduce 2
   ;; /*!
   ;; * \brief Serial loops with loop carry dependency,
   ;; *  the iteration must execute in order.
   ;; *  Cannot be re-ordered.
   ;; *
   ;; *  Disallow: reorder/parallel/vectorize
   ;; */
   :ordered 3
   ;; /*!
   ;; * \brief IterVar is opaque,
   ;; *
   ;; *  May not corresponds to any generated loop
   ;; *  Disallow all IterVar manipulations and compute_at
   ;; *
   ;; * \note This is usually used to implement composite op
   ;; *  or external op, where the
   ;; */
   :opaque 4
   ;; // The following are possible additional
   ;; // types that are provided during schedule
   ;; /*!
   ;; * \brief The execution is unrolled.
   ;; */
   :unrolled 5
   ;; /*!
   ;; * \brief The loop is vectorized.
   ;; */
   :vectorized 6
   ;; /*!
   ;; * \brief The loop is parallelized.
   ;; */
   :parallelized 7
   ;; /*!
   ;; * \brief Marks boundary of tensorization intrinsic.
   ;; */
   :tensorized 8})


(def iteration-variable-type-set (set (keys iteration-variable-types)))


(defn iteration-variable
  "Create a variable that controls iteration through the data.  The iteration type
affects the class of optimizations that the compiler is able to apply to the affected
expressions,

    Parameters
    ----------
    dom : Range
        The domain of iteration.

    name : str
        The name of iteration variable.

    iteration-type : keyword
        The type of iteration.

    thread-tag : str
        The thread tag of the iteration variable."
  [domain name iteration-type & {:keys [thread-tag]
                                 :or {thread-tag ""}}]

  (when-not-error (iteration-variable-type-set iteration-type)
    (ex-info "Iteration type not in allowed iteration types"
             {:allowed-types iteration-variable-type-set
              :iteration-type iteration-type}))

  (let [domain (when domain
                 (if (= :range (bindings/get-node-type domain))
                   domain
                   (range (first domain) (second domain))))
        v (variable name)]
    (bindings/global-node-function "_IterVar" domain v
                            (iteration-variable-types iteration-type)
                            thread-tag)))


(defn name->thread-axis-iterator
  "Create a thread iter-var from a thread axis name"
  [axis-name]
  (iteration-variable nil axis-name :thread-index :thread-tag axis-name))


(defmacro tvm-fn
  "Like (fn) but retains the arglists.  Lambda in clojure unfortunately does not."
  [arg-vec & body]
  (let [retval `(fn ~arg-vec
                  ~@body)]
    (with-meta retval {:arglists `(quote ~arg-vec)})))


(defn compute
  "Construct a new tensor by computing over the shape domain.

    The compute rule is result[axis] = fcompute(axis)

    Parameters
    ----------
    shape: Array of Expr
        The shape of the tensor

    fcompute: lambda function of indices-> value
        Specifies the input source expression

    name: str, optional
        The name hint of the tensor

    tag: str, optional
        Additonal tag information about the compute.

    attrs: dict, optional
        The additional auxiliary attributes about the compute.

    Returns
    -------
    The created compute node
    "
  [shape fcompute name & {:keys [tag attrs]
                     :or {tag ""}}]
  (let [fn-arglists (->> (meta fcompute)
                         :arglists
                         (mapv (comp safe-str clojure.core/name)))]
    (when-not-error fn-arglists
      (ex-info "Functions passed into compute must have the arglists in their metadata"
               {}))
    (when-not-error (= (count shape)
                       (count fn-arglists))
      (ex-info "fcompute must have same number of args as rank of shape"
               {:shape-rank (count shape)
                :num-fn-args (count fn-arglists)}))
    (let [compute-dim (map (fn [arg-name shape-value]
                             (iteration-variable [0 shape-value] arg-name
                                                 :data-parallel))
                           fn-arglists shape)
          body-data (apply fcompute (map :var compute-dim))
          body-data (if-not (instance? clojure.lang.Sequential body-data)
                      [body-data]
                      body-data)]
      (bindings/g-fn "_ComputeOp" (safe-str name) tag attrs compute-dim body-data))))


(defn commutative-reduce
  "1 left hand side, first var of reduce operation
  N right hand sides, rest of the variables of the reduce operation
  identity-val - initialization of left hand side.n
  expr-ary - one for each (const) right hand side.
  dtype - datatype of all inputs to reduction"
  [reduce-op identity-val dtype expr-seq axis-seq]
  (let [fn-arglists (->> (meta reduce-op)
                         :arglists
                         (map clojure.core/name)
                         (mapv #(variable % :dtype dtype)))
        reduce-ast [(apply reduce-op fn-arglists)]
        lhs-vars (take 1 fn-arglists)
        rhs-vars (drop 1 fn-arglists)
        comm-reducer (bindings/g-fn "make.CommReducer"
                             lhs-vars rhs-vars
                             (->node reduce-ast)
                             (->node [identity-val]))]
    (bindings/g-fn "make.Reduce" comm-reducer expr-seq axis-seq (->node true) 0)))


(defn output-tensors
  [compute-op]
  (->> (clojure.core/range (bindings/global-function "_OpNumOutputs" compute-op))
       (mapv #(bindings/global-node-function "_OpGetOutput" compute-op (int %1)))))


(defn input-tensors
  [compute-op]
  (->> (bindings/global-node-function "_OpInputTensors" compute-op)
       bindings/tvm-array->jvm))

(defn throw-nil
  [item key-val]
  (if-let [retval (get item key-val)]
    retval
    (throw (ex-info "Expected object but got nil"
                    {:item item
                     :key key-val}))))


(defn ->operation
  [tens-or-op]
  (case (:tvm-type-kwd tens-or-op)
    :tensor (throw-nil tens-or-op :op)
    :compute-operation tens-or-op
    :scan-operation tens-or-op
    :placeholder-operation tens-or-op
    :external-operation tens-or-op))


(defn create-schedule
  [op-seq]
  (let [op-seq (->> (if-not (sequential? op-seq)
                      [op-seq]
                      op-seq)
                    (mapv ->operation))]
    (bindings/g-fn "_CreateSchedule" op-seq)))


(defn ->stage
  [stage-or-schedule operation]
  (case (:tvm-type-kwd stage-or-schedule)
    :stage stage-or-schedule
    :schedule (throw-nil (:stage_map stage-or-schedule)
                         (->operation operation))))


(defmethod jna-node/get-extended-node-value :schedule
  [node-handle item-key]
  (->stage node-handle (->operation item-key)))


(defn stage-split-axis
  [stage iter-var factor]
  (bindings/tvm-array->jvm (bindings/g-fn "_StageSplitByFactor" stage iter-var factor)))


(defn stage-bind
  "Bind an iter-var to a stage variable"
  [stage iter-var thread-ivar]
  (bindings/g-fn "_StageBind" stage iter-var thread-ivar))


(defn stage-compute-at
  "Compute src stage at dst stage dst axis"
  [src-stage dst-stage dst-axis]
  (bindings/g-fn "_StageComputeAt" src-stage dst-stage dst-axis))


(defn stage-fuse
  "Fuse n-axis together, returns single new axis"
  [stage axis-args]
  ;;If there is only one axis, then fusing is pointless
  (if (= 1 (count axis-args))
    (first axis-args)
    (bindings/g-fn "_StageFuse" stage axis-args)))


(defn stage-parallel
  "Indicate that this axis has complete parallelism"
  [stage axis]
  (bindings/g-fn "_StageParallel" stage axis))


(defn stage-inline
  [stage]
  (bindings/g-fn "_StageComputeInline" stage))


(defn stage-tile
  [stage outer-axis inner-axis outer-dim inner-dim]
  (->
   (bindings/g-fn "_StageTile" stage outer-axis inner-axis outer-dim inner-dim)
   bindings/tvm-array->jvm))


(defn stage-reorder
  [stage axis-seq]
  (bindings/g-fn "_StageReorder" stage axis-seq))


(defn stage-vectorize
  [stage axis]
  (bindings/g-fn "_StageVectorize" stage axis))


(defn stage-unroll
  [stage axis]
  (bindings/g-fn "_StageUnroll" stage axis))


(defn schedule-cache-write
  "Returns a new tensor"
  [schedule tensor cache-type]
  (let [retval (bindings/g-fn "_ScheduleCacheWrite" schedule tensor cache-type)]
    {:tensor retval
     :schedule schedule}))


(defn schedule-cache-read
  [schedule tensor cache-type readers]
  (throw (ex-info "Unimplemented" {})))


(defn stage-bind-gpu
  "Bind the gpu-defined axis to the tvm axis.
  GPU (cuda, opencl) define a roughly level stage breakdown of axis: block and thread.
  Threads run on the same block and can share a special kind of memory (called shared
  memory).  There can be up to 3 tvm axis per block or thread and these are labeled
  (outer iterator to inner iterator):
  [z y x]"
  [stage block-axis-seq thread-axis-seq]
  (let [axis-names ["z" "y" "x"]
        full-info-fn (fn [grp-name axis-seq]
                         (map vector
                              (repeat grp-name)
                              axis-seq
                              ;;map to axis such that if you have one, it becomes
                              ;;the x axis.  If you have 2, first is y and second
                              ;;is x, etc.
                              (drop (- 3 (count axis-seq)) axis-names)))]
    (when-not (and (<= (count block-axis-seq) 3)
                   (<= (count thread-axis-seq) 3))
      (throw (ex-info "Block, threads can have up to 3 axis"
                      {:thread-axis-count (count thread-axis-seq)
                       :block-axis-count (count block-axis-seq)})))
    (->> (concat (full-info-fn "blockIdx" block-axis-seq)
                 (full-info-fn "threadIdx" thread-axis-seq))
         (map (fn [[grp-name axis gpu-axis-name]]
                (stage-bind stage axis
                            (name->thread-axis-iterator
                             (str grp-name "." gpu-axis-name)))))
         dorun)))


(defn stage-gpu-injective
  [stage op & {:keys [thread-count axis]
               :or {thread-count 16}}]

  (let [retval stage
        op (->operation op)
        stage (->stage stage op)
        fused-axis (stage-fuse stage (or axis (:axis op)))
        [bx tx] (stage-split-axis stage fused-axis thread-count)]
    (stage-bind-gpu stage [bx] [tx])
    retval))


(defn stage-cpu-injective
  [stage op & {:keys [axis]}]
  (let [retval stage
        op (->operation op)
        stage (->stage stage op)
        fused-axis (stage-fuse stage (or axis (:axis op)))]
    (stage-parallel stage fused-axis)
    retval))


(def default-build-config
  "Comments from tvm/build_module.h"
  {;;/*! \brief Threshold of number of steps in the loop to be automatically unrolled */
   :auto-unroll-max-step 0
   ;;/*! \brief The maximum nested level of loops that can be automatically unrolled */
   :auto-unroll-max-depth 8
   ;;/*! \brief The maximum extent of loop that will be unrolled */
   :auto-unroll-max-extent 0
   ;; /*!
   ;; * \brief Whether to explicitly unroll the loop. If set to false, the unroll hint will
   ;; * be passed to the CodeGen phase. Set to true if CodeGen supports unroll pragma.
   ;; */
   :unroll-explicit? true
   ;;/*! \brief Whether to detect global barrier */
   :detect-global-barrier? false
   ;;/*! \brief Whether to partition const loop */
   :partition-const-loop? false
   ;; /*!
   ;; * \brief The offset factor to use when constructing buffers. If this is set to
   ;; * 0, then the offset field is not used.
   ;; */
   :offset-factor 0
   ;; /*!
   ;; * \brief The data alignment to use when constructing buffers. If this is set to
   ;; * -1, then TVM's internal default will be used
   ;; */
   :data-alignment -1
   ;;/*! \brief Set to true if buffer arguments do not overlap. This enables more optimization. */
   :restricted-func? true
   ;; /*!
   ;; * \brief Splitting factor for loop splitting. If this is set to zero, no splitting will be
   ;; * done. Otherwise, a split will be done with this factor and the inner loop will be unrolled.
   ;; */
   :double-buffer-split-loop 1})


(defn declare-buffer
  "Decleare a new symbolic buffer.

    Normally buffer is created automatically during lower and build.
    This is only needed if user want to specify their own buffer layout.

    See the note below for detailed discussion on usage of buffer.

    Parameters
    ----------
    shape : tuple of Expr
        The shape of the buffer.

    dtype : str, optional
        The data type of the buffer.

    name : str, optional
        The name of the buffer.

    data : Var, optional
        The data pointer in the buffer.

    strides: array of Expr
        The stride of the buffer.

    elem_offset: Expr, optional
        The beginning offset of the array to data.
        In terms of number of elements of dtype.

    scope: str, optional
        The storage scope of the buffer, if not global.
        If scope equals empty string, it means it is global memory.

    data_alignment: int, optional
        The alignment of data pointer in bytes.
        If -1 is passed, the alignment will be set to TVM's internal default.

    --CN - REMOVED - No one understands what this does.  It is only referenced in the
  code in order to perform a check during argument binding.  So the description below is
  accurate for what it is worth but it is hard to me to see how this is useful.


    offset_factor: int, optional
        The factor of elem_offset field, when set,
        elem_offset is required to be multiple of offset_factor.
        If 0 is pssed, the alignment will be set to 1.
        if non-zero is passed, we will created a Var for elem_offset if elem_offset is
  not None.

     --CN - END-REMOVED --

    Returns
    -------
    buffer : Buffer
        The created buffer

    Note
    ----
    Buffer data structure reflects the DLTensor structure in dlpack.
    While DLTensor data structure is very general, it is usually helpful
    to create function that only handles specific case of data structure
    and make compiled function benefit from it.

    If user pass strides and elem_offset is passed as None
    when constructing the function, then the function will be specialized
    for the DLTensor that is compact and aligned.
    If user pass a fully generic symbolic array to the strides,
    then the resulting function becomes fully generic."
  [shape & {:keys [dtype name data strides elem-offset scope data-alignment]
            :or {name "buffer" dtype "float32" scope "" data-alignment -1}}]
  (let [shape (if (sequential? shape)
                shape
                [shape])
        elem-offset (if elem-offset elem-offset 0)
        data (if data data (variable name :dtype "handle"))
        offset-factor 0]
    (bindings/global-node-function "_Buffer"
                            data (->dtype dtype) shape strides elem-offset
                            (safe-str name) scope
                            data-alignment offset-factor)))


(defn bind-arguments
  "Given an arg-list and existing bind map, produce a new arg list
and bind map with all arguments bound to input buffers with defined buffer layout.
Bind map is a map of type NodeHandle->NodeHandle where the keys are tensors and the
values are buffers.  The default is to bind a compact, non-offset buffer so if you want
a different buffer type than this then you need to bind it yourself."
  [arg-list bind-map build-config]
  (reduce (fn [[arg-list bind-map] arg]
            (condp = (bindings/get-node-type arg)
              :tensor
              (if-let [buf (bind-map arg)]
                [(conj arg-list buf) bind-map]
                (let [shape (:shape arg)
                      new-buf (declare-buffer
                               (:shape arg) :dtype (:dtype arg)
                               :data-alignment (:data-alignment build-config))]
                  [(conj arg-list new-buf) (assoc bind-map arg new-buf)]))
              :buffer
              [(conj arg-list arg) bind-map]
              :variable
              [(conj arg-list arg) bind-map]))
          [[] bind-map]
          arg-list))


(defn- gfnr
  "Like global-node-function but the first argument is assumed to be the 'this' object
and the second is the function to call.  We need this slight transposition in order to use
the threading macro with the long set of ir pass possibilities."
  [item fn-name & args]
  ;;These are all nodes but don't upack fields; this causes too much unnecessary unpacking.
  (apply bindings/g-fn fn-name item args))



(def lowered-function-type->int-map
  {:mixed-function 0
   :host-function 1
   :device-functions 2})


(def int->lowered-function-type-map (c-set/map-invert lowered-function-type->int-map))


(defn schedule->lowered-function
  "Lowering step before build into target.

    Parameters
    ----------
    schedule : tvm.Schedule
        The schedule to be builded

    args : list of Buffer or Tensor or Var
        The argument lists to the function.

    name : str, optional
        The name of result function.

    bind-map: map of {:tensor :buffer}, optional
        mapping fuction or hash-map that maps the Tensor to Buffer which specified the
  data layout
        requirement of the function. By default, a new compact buffer is created
        for each tensor in the argument list.

    simple-mode? : bool, optional (not currently implemented)
        Whether only output simple and compact statement, this will skip
        LoopPartition, api wrapper generation and Unrolling.

    Returns
    -------
    f : LoweredFunc or Stmt
       The result function, if with_api_wrapper=False
       Then the Stmt before make api is returned.
    "
  [schedule args name
   & {:keys [build-config bind-map simple-mode?]
      :or {bind-map {}
           build-config
           default-build-config}}]
  (let [schedule (bindings/g-fn "_ScheduleNormalize" schedule)
        [arg-list bind-map] (bind-arguments args bind-map build-config)
        bounds (bindings/g-fn "schedule.InferBound" schedule)
        cache-line-size 64]
    (-> schedule
        ;;Phase 0
        (gfnr "schedule.ScheduleOps" bounds)
        (gfnr "ir_pass.InjectPrefetch")
        ;;Phase 1
        (gfnr "ir_pass.StorageFlatten" bind-map cache-line-size)
        (gfnr "ir_pass.CanonicalSimplify")
        ;;Phase 2
        ((fn [stmt]
           (if simple-mode?
             stmt
             (gfnr stmt "ir_pass.LoopPartition"
                   (:partition-const-loop? build-config)))))
        (gfnr "ir_pass.VectorizeLoop")
        (gfnr "ir_pass.InjectVirtualThread")
        (gfnr "ir_pass.InjectDoubleBuffer" (:double-buffer-split-loop build-config))
        (gfnr "ir_pass.StorageRewrite")
        (gfnr "ir_pass.UnrollLoop"
              (:auto-unroll-max-step build-config)
              (:auto-unroll-max-depth build-config)
              (:auto-unroll-max-extent build-config)
              (:unroll-explicit? build-config))
        ;;Phase 3
        (gfnr "ir_pass.Simplify")
        (gfnr "ir_pass.LowerStorageAccessInfo")
        (gfnr "ir_pass.RemoveNoOp")
        (gfnr "ir_pass.RewriteUnsafeSelect")
        ((fn [stmt]
           (if simple-mode?
             stmt
             (-> stmt
                 ;;Exit
                 (gfnr "ir_pass.MakeAPI" name arg-list 0
                       (:restricted-func? build-config))
                 (update :func_type int->lowered-function-type-map))))))))


(defn node->str
  [node]
  (bindings/g-fn "_format_str" node))


(defn schedule->str
  [schedule arg-list fn-name]
  (-> (schedule->lowered-function schedule arg-list fn-name :simple-mode? true)
      node->str))


(def target-name->props
  [[#{:llvm :cpu} {:keys #{:cpu}}]
   [#{:cuda :nvptx} (fn [target-name]
                        {:keys #{:cuda :gpu}
                         :max-num-threads 512
                         :thread-warp-size 32})]
   [#{:rocm :opencl} (fn [target-name]
                         {:keys #{:rocm :gpu}
                          :max-num-threads 256})]
   [#{:metal :vulkan} (fn [target-name]
                          {:keys #{:gpu target-name}
                           :max-num-threads 256})]
   [#{:opengl} (fn [target-name]
                  {:keys #{:opengl}})]])

(defn target-info
  [target-name]
  (let [target-map-fn (->> target-name->props
                           (filter #((first %) target-name))
                           first
                           second)]
    (when-not-error target-map-fn
      (ex-info "Failed to find target properties in target"
               {:target-name target-name}))
    (merge {:target-name target-name
            :thread-warp-size 1}
           (target-map-fn target-name))))


(defn target-name->thread-warp-size
  ^long [target-name]
  (long
   (:thread-warp-size (target-info target-name))))


(defn lowered-functions->module
  [lowered-function-seq & {:keys [build-config target-name target-host]
                           :or {target-name :llvm
                                target-host :llvm
                                build-config default-build-config}}]
  (let [arg-type-list (map bindings/get-node-type lowered-function-seq)]
    (when-not-error (= #{:lowered-function} (set arg-type-list))
      (ex-info "Argumentis not a sequence of lowered functions"
               {:arg-types arg-type-list})))
  (let [arg-name-set (->> (map :name lowered-function-seq)
                          set)
        _ (when-not-error (= (count lowered-function-seq)
                             (count arg-name-set))
            (ex-info "Arguments have duplicate names or are themselves duplicated"
                     {:arg-names (mapv :name lowered-function-seq)}))
        [host-fns device-fns]
        (reduce (fn [[host-fns device-fns] lowered-fn]
                  (condp = (:func_type lowered-fn)
                    :host-function
                    [(conj host-fns lowered-fn) device-fns]
                    :device-function
                    [host-fns (conj device-fns lowered-fn)]
                    :mixed-function
                    (let [warp-size (long (target-name->thread-warp-size target-name))
                          fsplits (-> (if (:detect-global-barrier? build-config)
                                        (bindings/g-fn "ir_pass.ThreadSync" lowered-fn "global")
                                        lowered-fn)
                                      (gfnr "ir_pass.LowerThreadAllreduce" warp-size)
                                      (gfnr "ir_pass.SplitHostDevice")
                                      (bindings/tvm-array->jvm))]
                      [(conj host-fns (first fsplits))
                       (concat device-fns (rest fsplits))])))
                [[] []]
                lowered-function-seq)
        host-fns
        (mapv (fn [host-fn]
                (bindings/g-fn "ir_pass.BindDeviceType" host-fn
                               (bindings/device-type->int target-host))
                (-> (bindings/g-fn "ir_pass.LowerTVMBuiltin" host-fn)
                    (gfnr "ir_pass.LowerIntrin" (name target-host))
                    (gfnr "ir_pass.CombineContextCall")))
              host-fns)
        ^runtime$TVMModuleHandle mhost (bindings/g-fn "codegen._Build" host-fns
                                               (name target-host))]
    (when (seq device-fns)
      (resource/with-resource-context
        (->> (mapv #(bindings/g-fn "ir_pass.LowerIntrin" % (name target-name)) device-fns)
             (#(bindings/g-fn "codegen._Build" % (name target-name)))
             (bindings/mod-import mhost))))
    mhost))


(defn schedules->fns
  "Given a sequence of schedule-data, return a map of name to clojure
  callable function.
  A module is created and added to the resource context transparently.
  Schedule data:
  {:name :fn-name
   :arglist arguments
   :schedule schedule
   :bind-map (optional) bind-map
  }
  returns:
  {:module module
   :fn-map map of name->IFn (clojure callable function.)"
  [sched-data-seq & {:keys [build-config
                            target-name
                            target-host]
                     :or {build-config default-build-config
                          target-name :llvm
                          target-host :llvm}}]
  (let [sched-data-seq (map (fn [{:keys [name] :as entry}]
                              (assoc entry :c-name
                                     (safe-str name)))
                            sched-data-seq)
        lowered-functions
        (mapv (fn [{:keys [c-name arglist schedule bind-map]}]
                             (schedule->lowered-function
                              schedule arglist c-name
                              :build-config build-config
                              :bind-map (or bind-map {})))
                           sched-data-seq)
        module (lowered-functions->module
                lowered-functions
                :build-config build-config
                :target-name target-name
                :target-host target-host)]
    {:module module
     :fn-map
     (->> sched-data-seq
          (map (fn [{:keys [c-name name] :as seq}]
                 (let [mod-fn (bindings/get-module-function module c-name)]
                   [name (fn [& args]
                           (apply bindings/call-function mod-fn args))])))
          (into {}))}))


(extend-protocol bindings-proto/PConvertToNode
  Boolean
  (->node [item] (const item "uint1x1"))
  Byte
  (->node [item] (const item "int8"))
  Short
  (->node [item] (const item "int16"))
  Integer
  (->node [item] (const item "int32"))
  Long
  (->node [item] (const item "int64"))
  Float
  (->node [item] (const item "float32"))
  Double
  (->node [item] (const item "float64"))
  clojure.lang.Sequential
  (->node [item] (apply bindings/tvm-array (map ->node item))))
