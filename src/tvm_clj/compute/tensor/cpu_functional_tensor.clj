(ns tvm-clj.compute.tensor.cpu-functional-tensor
  (:require [tvm-clj.compute.tensor.functional-protocols :as fp]
            [tech.compute.cpu.driver :as cpu-driver]
            [tech.compute.tensor :as ct]
            [tech.compute.cpu.tensor-math]
            [tech.datatype.core :as dtype-core]
            [tech.compute.driver :as drv])
  (:import [tech.compute.cpu.driver CPUStream]))



(extend-type CPUStream
  fp/PFunctionalBackend
  ;;Because we have defined a slightly different language the base tensor
  ;;won't work out of the box.  The CPU system expects a defined format for
  ;;select
  (select [stream item args]
    (apply ct/select item
           (map (fn [arg]
                  (cond
                    (keyword? arg)
                    arg
                    (sequential? arg)
                    (let [int-data (int-array arg)]
                      (ct/->Tensor (drv/get-device stream)
                                   {:shape [(alength int-data)]
                                    :strides [1]}
                                   (dtype-core/->view (int-array arg))))))
                args)))
  (static-cast [stream item dtype]
    (let [retval (ct/new-tensor (ct/shape item) :datatype dtype :init-value nil)]
      (ct/assign! retval item)
      retval))

  (binary-op [stream lhs rhs op]
    (let [retval (ct/new-tensor (ct/shape lhs)
                                :datatype (ct/get-datatype lhs)
                                :init-value nil)]
      (ct/binary-op! retval 1.0 lhs 1.0 rhs op)
      retval)))
