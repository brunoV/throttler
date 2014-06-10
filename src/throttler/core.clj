(ns throttler.core
  (:require [clojure.core.async :as async :refer [chan <!! >!! >! <! timeout go close! dropping-buffer]]))

;; To keep the throttler precise even for high frequencies, we set up a
;; minimum sleep time. In my tests I found that below 10 ms the actual
;; sleep time has an error of more than 10%, so we stay above that.
(def ^:private min-sleep-time 10)

(defn- round [n] (Math/round (double n)))

(def ^:private unit->ms
  {:microsecond 0.001 :minute 60000
   :millisecond 1     :hour   3600000
   :second      1000  :day    86400000})

(defn- ^:private mapply
  "Like apply, but applies a map to a function with positional map
  arguments. Can take optional initial args just like apply."
  ([f m] (apply f (apply concat m)))
  ([f arg & args] (apply f arg (concat (butlast args) (apply concat (last args))))))

(defmacro ^:private pipe [from to]
  "Pipes an element from the from channel and supplies it to the to
   channel. The to channel will be closed when the from channel closes.
   Must be called within a go block. Returns true if the put succeeded,
   or nil if the input channel is closed."
  `(let [v# (<! ~from)]
     (if (nil? v#)
       (close! ~to)
       (>! ~to v#))))

(defmacro ^:private put-tokens! [c n]
  "Puts n tokens into channel c. Returns false if the channel is closed
  before all tokens are inserted, true otherwise. Must be called within
  a go block."
  `(loop [[t# & r#] (repeat ~n :token)]
     (if t#
       (when (>! ~c t#)
         (recur r#))    ; put succeeded
       true)))

(defn- chan-throttler* [rate-ms bucket-size token-value]
  (let [sleep-time (max (/ token-value rate-ms) min-sleep-time)
        token-value (max (round (* sleep-time rate-ms)) token-value)
        ; We have to make sure that at least token-value messages can fit
        ; in the bucket. Otherwise, a large number of tokens can be lost
        ; just because there was no reader - this can be particularly
        ; important for large token-values.
        bucket-size (max bucket-size token-value)
        ; Model the bucket with a buffered channel.
        bucket (chan (dropping-buffer bucket-size))
        ; timeout expects an int, and will fail silently otherwise
        sleep-time (int (round sleep-time))]

    ;; The bucket filler thread. Puts token-value tokens in the bucket every
    ;; sleep-time seconds. If the bucket is full the token is dropped
    ;; since the bucket channel uses a dropping buffer.

    ;; For high frequencies, we leave sleep-time fixed to
    ;; min-sleep-time, and we increase token-value, the number of
    ;; messages to pipe per token. For low frequencies, the token-value
    ;; is 1 and we adjust sleep-time to obtain the desired rate.

    (go
      (loop []
        (when (put-tokens! bucket token-value)
          (<! (timeout sleep-time))
          (recur))))

    ;; The piping thread. Takes a token from the bucket (blocking until
    ;; one is ready if the bucket is empty), and forwards one message
    ;; from the source channel to the output channel.
    (fn [c]
      (let [c' (chan)]        ; the throttled chan
        (go
          (loop []
            (<! bucket)       ; block for a token
            (when (pipe c c') ; pipe a single message
              (recur)))
          (close! bucket))    ; close bucket if input channel closes
        c'))))

(defn- granularity->token-value [rate-ms g]
  (if (keyword? g)
    (do
      (assert (contains? unit->ms g)
              (str "Granularity " g " does not correspond to a known unit."
                   " Available units are: " (keys unit->ms)))
      (max (int (* (unit->ms g) rate-ms)) 1))
    (do
      (assert (integer? g) (str "Granularity " g " is neither a unit nor an integer"))
      (assert (pos? g) (str "Granularity value " g " should be positive"))
      g)))

(defn chan-throttler
  "Returns a function that will take an input channel and return an
   output channel with the desired rate. Accepts the same optional keys
   as [[throttle<]].

   If the throttling function returned here is used on more than one
   channel, they will all share the same token-bucket. This means their
   overall output rate combined will be equal to the provided rate. In
   other words, they will all share the alloted bandwith using
   statistical multiplexing.

   See [[fn-throttler]] for an example that can trivially be extrapolated
   to [[chan-throttler]]."

  [rate unit & {:keys [granularity burst] :or {granularity 1 burst 0}}]

  {:pre [(unit->ms unit)
         (number? rate)
         (pos? rate)
         (integer? burst)
         (not (neg? burst))]}

  (let [rate-ms (/ rate (unit->ms unit))]
    (chan-throttler* rate-ms burst (granularity->token-value rate-ms granularity))))

(defn throttle<
  "Takes a write channel, a goal rate and a unit and returns a read
   channel. Messages written to the input channel can be read from
   the throttled output channel at a rate that will be at most the
   provided goal rate.

   The throttled channel will be closed when the input channel
   closes.

   Optional settings:

   * `:burst` - The burst size, or number of tokens that can be stored
      in the bucket.
   * `:granularity` - Can be either an integer or a keyword representing
      a time unit (`:second`, `:millisecond`, etc). The granularity
      specifies how tightly the throttler controls the rate. A
      granularity of 1 means that the rate is enforced on each message.
      A granularity of 100 will let 100 messages through before further
      restricting the rate. The global rate is unaffected.
      If it is a unit, then the granularity will be set to however many
      messages per unit the rate dictates. So for example, a rate of
      `10 :second` and a granularity of `:second` is equivalent to
      setting granularity to 10.

   As an example, the channel produced by calling:

       (throttle< (chan) 1 :second :burst 9)

   Will transmit 1 message/second on average but can transmit up to
   10 messages on a single second (9 burst messages + 1 message/second).

   Note that after the burst messages have been consumed they have to
   be refilled in a quiescent period at the provided rate, so the overall
   goal rate is not affected in the long term.

   Another example, using a higher granularity:

       (throttle< c 1000 :hour :granularity :hour)

   The channel returned here will limit messages to 1000 per hour but with
   no \"traffic shaping\" within the hour. So all 1000 messages can be
   taken in the first second of the hour; the rate will not be enforced
   in smaller time intervals. As a comparison, with the default
   granularity of 1 taking 1000 messages would have taken a full hour
   (every take would have been spaced by about 3.6 seconds)."

  [c rate unit & {:as opts}]
  ((mapply chan-throttler rate unit opts) c))

(defn fn-throttler
  "Creates a function that will globally throttle multiple functions at
   the provided rate.  The returned function accepts a function and
   produces an equivalent one that complies with the desired rate. If
   applied to many functions, the sum af all their invocations in a time
   interval will sum up to the goal average rate.

   Example:

       ; create the function throttler
       (def slow-to-1-per-minute (fn-throttler 1 :minute)

       ; create slow versions of f1, f2 and f3
       (def f1-slow (slow-to-1-per-minute f1)
       (def f2-slow (slow-to-1-per-minute f2)
       (def f3-slow (slow-to-1-per-minute f3)

       ; use them to do work. Their aggregate rate will be equal to 1
       ; call/minute
       (f1-slow arg1 arg2) ; => result, t = 0
       (f2-slow)           ; => result, t = 1 minute
       (f3-slow arg)       ; => result, t = 2 minutes

   The combined rate of `f1-slow`, `f2-slow` and `f3-slow` will be
   equal to 'rate'. This does not mean that the rate of each is 1/3rd of
   'rate'; if only `f1-slow` is being called then its throughput will be
   close to rate. Or, if one of the functions is being called from
   multiple threads then it'll get a greater share of the total bandwith.

   In other words, the functions will use statistical multiplexing to
   cap the allotted bandwidth.

   Accepts the same optional keys as [[throttle<]] for controlling
   burstiness and granularity."

  [rate unit & {:as opts}]
  (let [in (chan 1)
        out (mapply throttle< in rate unit opts)]

    ;; This function takes a function and produces a throttled
    ;; function. When called multiple times, all the resulting
    ;; throttled functions will share the same throttled channel,
    ;; resulting in a globally shared rate. I.e., the sum af the
    ;; rates of all functions will be at most the argument rate).

    (fn [f]
      (fn [& args]
        ;; The approach is simple: pipe a bogus message through a
        ;; throttled channel before evaluating the original function.

        (>!! in :eval-request)
        (<!! out)
        (apply f args)))))

(defn throttle-fn
  "Takes a function, a goal rate and a time unit and returns a
  function that is equivalent to the original but that will have a
  maximum throughput of 'rate'.

  Accepts the same optional keys as [[throttle<]] for controlling
  burstiness and granularity."

  [f rate unit & {:as opts}]
  ((mapply fn-throttler rate unit opts) f))