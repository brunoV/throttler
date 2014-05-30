(ns throttler.bench
  (:require [criterium.core :as c :refer [time-body]]))

(defn rate [f expected-in-s]
  (let [n (* 2 expected-in-s)
        time-ns (first (time-body (dotimes [_ n] (f))))
        time-s (/ time-ns 1E9)
        rate (double (/ n time-s))]
    rate))

(defn combined-rate [fs expected-rate-s]
  (rate #((rand-nth fs)) expected-rate-s))
