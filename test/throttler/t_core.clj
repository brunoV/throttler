(ns throttler.t-core
  (:use midje.sweet)
  (:require [clojure.core.async :as async :refer [chan timeout <!! >!! close! alts!!]]
            [throttler.core :refer :all]
            [throttler.bench :refer [rate]]))

(facts "about throttle-fn"
  (let [+?  (throttle-fn + 10       :second)
        +?? (throttle-fn + 0.00001  :microsecond)] ; same, but expressed differently

    (fact "It returns something"
      (throttle-fn + 1 :second) => truthy
      (throttle-fn + 1 :second :burst 9) => truthy
      (throttle-fn + 1 :second :granularity :second) => truthy
      (throttle-fn + 1 :second :granuarity 10) => truthy
      (throttle-fn + 1 :second :granularity 10 :burst 9) => truthy)

      (fact "It acts like the original function"
        (+? 1 1) => (+ 1 1)
        (+?) => (+)
        (+? 1 1 1.2) => (+ 1 1 1.2))

      (fact "It runs at approximately the desired rate"
        (rate (fn [] (+?  1 1)) 10) => (roughly 10 2)
        (rate (fn [] (+?? 1 1)) 10) => (roughly 10 2))

      (fact "It fails graciously with wrong arguments"
        (throttle-fn +  1   :foo)       => (throws IllegalArgumentException #"units")
        (throttle-fn + -1   :hour)      => (throws IllegalArgumentException)
        (throttle-fn + :foo :hour)      => (throws IllegalArgumentException)
        (throttle-fn +  0   :hour)      => (throws IllegalArgumentException)
        (throttle-fn +  1   :hour :burst :foo) => (throws IllegalArgumentException)
        (throttle-fn +  1   :hour :burst -1)   => (throws IllegalArgumentException)
        (throttle-fn +  1   :hour :granularity :foo) => (throws)
        (throttle-fn +  1   :hour :granularity 0)    => (throws)
        (throttle-fn +  1   :hour :granularity -1)   => (throws))))

(facts "about throttle-chan"
  (let [in (chan 1)
        out (throttle-chan in 10 :second)]

   (fact "acts like a piped channel"
     (>!! in :token)
     (<!! out) => :token)

   (fact "closing the input closes the output"
     (close! in)
     (<!! out) => nil)))

(facts "about granularity"

  (let [in (chan 10)
        out (throttle-chan in 10 :second :burst 10 :granularity 10)]

  (fact "When granularity is set to 10, we can take 10 messages immediately"
    (dotimes [_ 10] (>!! in :message))
    (dotimes [_ 10] (async/alts!! [out (timeout 0)] :priority true) => [:message out]))

  (fact "But the next take would have to wait"
    (let [t (timeout 0)]
      (async/alts!! [out t] :priority true) => [nil t])))

  (let [in (chan 7)
        out (throttle-chan in 7 :second :granularity :second)]

    (fact "When granularity is set to :second with a rate of 7 :second, we can take 7 messages immediately"
      (dotimes [_ 7] (>!! in :message))
      (dotimes [_ 7] (alts!! [out (timeout 0)] :priority true) => [:message out]))

    (fact "But the next take would have to wait"
      (let [t (timeout 0)]
        (alts!! [out t] :priority true) => [nil t]))))
