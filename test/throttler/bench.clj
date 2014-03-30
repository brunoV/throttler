(ns throttler.bench
  (:require [criterium.core :as c :refer [time-body]]
            [throttler.core :as t :refer [throttle-chan]]
            [clojure.core.async :as async :refer [<!! >!! chan]]))

(def very-fast      (let [in (chan 1)] [in (throttle-chan in 10 :millisecond)]))
(def fast           (let [in (chan 1)] [in (throttle-chan in 1 :millisecond)]))
(def slow           (let [in (chan 1)] [in (throttle-chan in 100 :second)]))
(def very-slow      (let [in (chan 1)] [in (throttle-chan in 10  :second)]))
(def very-very-slow (let [in (chan 1)] [in (throttle-chan in 1 :second)]))

(defn rate [f expected-in-s]
  (let [n (* 2 expected-in-s)
        time-ns (first (time-body (dotimes [_ n] (f))))
        time-s (/ time-ns 1E9)
        rate (double (/ n time-s))]
    rate))

(defn pipe-message [[in out]]
    (>!! in :token)
    (<!! out))
