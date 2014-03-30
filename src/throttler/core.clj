(ns throttler.core
  (:require [clojure.core.async :as async :refer [chan <!! >!! >! <! timeout go close! dropping-buffer]]
            [clojure.math.numeric-tower :as math :refer [round]]))

;; To keep the throttler precise even for high frequencies, we set up a
;; minimum sleep time.
(def min-sleep-time 10)

(def unit->ms
  {:microsecond 0.1 :millisecond 1
   :second 1000 :minute 60000
   :hour 3600000 :day 86400000})

(defn- pipe [from to]
  "Pipes an element from the from channel and supplies it to the to
   channel. The to channel will be closed when the from channel closes."
  (let [v (<!! from)]
    (if (nil? v)
      (close! to)
      (>!! to v))))

(defn- throttle-chan* [c rate-ms bucket-size]
  (let [sleep-time (round (max (/ rate-ms) 10))
        token-value (round (* sleep-time rate-ms))   ; how many messages to pipe per token
        c' (chan)                                    ; the throttled output channel
        bucket (chan (dropping-buffer bucket-size))] ; we model the bucket with a buffered channel

    ;; The piping thread. Takes a token from the bucket (blocking until
    ;; one is ready if the bucket is empty), and forwards token-value
    ;; messages from the source channel to the output channel.

    ;; For high frequencies, we leave sleep-time fixed to
    ;; min-sleep-time, and we increase token-value, the number of
    ;; messages to pipe per token. For low frequencies, the token-value
    ;; is 1 and we adjust sleep-time to obtain the desired rate.

    (go
     (while true
       (<! bucket) ; block for a token
       (dotimes [_ token-value]
         (pipe c c')))) ; pipe token-value messages with the token

    ;; The bucket filler thread. Puts a token in the bucket every
    ;; sleep-time seconds. If the bucket is full the token is dropped
    ;; since the bucket channel uses a dropping buffer.

    (go
     (while true
       (>! bucket :token)
       (<! (timeout (int sleep-time)))))

    c'))

(defn throttle-chan
  ([c rate unit]

     "Takes a write channel, a goal rate and a unit token and returns a
      read channel. Messages written to the input channel can be read
      from the throttled output channel at a rate that will be at most
      the provided goal rate.

      The throttled channel will be closed when the input channel
      closes."

     (throttle-chan c rate rate unit))

  ([c avg-rate burst-rate unit]

     "Like above, but with two goal rates: an average rate and a burst
      rate.

      The average rate is the overall read rate that the channel will
      offer over an extended period of time. The burst rate is the
      maximum peak rate allowed for the throttled channel over a short
      period of time equal to 'unit'.

      As an example, the channel produced by calling:

      (throttle-chan (chan) 1 10 :second)

      Will transmit 1 message/second on average but can transmit up to
      10 messages on a single second."

     (when (nil? (unit->ms unit)) (throw "Invalid unit"))
     (when (< burst-rate avg-rate)
       (throw "burst-rate may not be smaller than avg-rate"))

     (let [avg-rate-ms (/ avg-rate (unit->ms unit))
           bucket-size (max (- burst-rate avg-rate) 1)]
       (throttle-chan* c avg-rate-ms bucket-size))))

(defn throttle-fn
  ([f rate unit]

     "Takes a function, a goal rate and a time unit and returns a
      function that is equivalent to the original but that will have a maximum
      throughput of 'rate'."

     (throttle-fn f rate rate unit))

  ([f avg-rate burst-rate unit]

     "As above, but allowing temporary burst rates of up to
      'burst-rate'. See throttle-chan for an illustratoin of average and
      burst rates."

    (let [in (chan 1)
          out (throttle-chan in avg-rate burst-rate unit)]
       (fn [& args]

         ;; The approach is simple: pipe a bogus message through a
         ;; throttled channel before evaluating the provided function.

         (>!! in :eval-request)
         (<!! out)
         (apply f args)))))

(defn test-speed [in out n]
  (time
    (loop [i n]
      (>!! in :value)
      (<!! out)
      (when (pos? i) (recur (dec i))))))