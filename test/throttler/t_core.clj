(ns throttler.t-core
  (:use midje.sweet)
  (:require [clojure.core.async :refer [chan timeout <!! >!! close!]]
            [throttler.core :refer :all]
            [throttler.bench :refer [rate]]))

(facts "about throttle-fn"
  (let [+?  (throttle-fn + 10 :second)
        +?? (throttle-fn + (atom 0.00001) :microsecond)] ; same, but expressed differently

    (fact "It returns something"
      (throttle-fn + 1 :second) => truthy
      (throttle-fn + 1 :second 9) => truthy)

      (fact "It acts like the original function"
        (+? 1 1) => (+ 1 1)
        (+?) => (+)
        (+? 1 1 1.2) => (+ 1 1 1.2))

      (fact "It runs at approximately the desired rate"
        (rate (fn [] (+?  1 1)) 10) => (roughly 10 2)
        (rate (fn [] (+?? 1 1)) 10) => (roughly 10 2))

      (fact "It fails graciously with the wrong unit"
        (throttle-fn +  1   :foo)       => (throws IllegalArgumentException #"units")
        (throttle-fn + -1   :hour)      => (throws IllegalArgumentException)
        (throttle-fn + :foo :hour)      => (throws IllegalArgumentException)
        (throttle-fn +  0   :hour)      => (throws IllegalArgumentException)
        (throttle-fn +  1   :hour :foo) => (throws IllegalArgumentException)
        (throttle-fn +  1   :hour -1)   => (throws IllegalArgumentException))))

(facts "about throttle-chan"
  (let [in (chan 1)
        out (throttle-chan in 10 :second)]

   (fact "acts like a piped channel"
     (>!! in :token)
     (<!! out) => :token)

   (fact "closing the input closes the output"
     (close! in)
     (<!! out) => nil)))
