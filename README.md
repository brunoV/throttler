# Throttler

Throttles the throughput of function calls and `core.async` channels.

Uses the [token bucket algorithm][1] to control both the overall rate as
well as the burst rate.

## Get it

### Leiningen

```clj
[throttler "0.1.1"]
```

### Gradle

```scala
compile "throttler:throttler:0.1.1"
```

## Throttling functions

Import `throttler.core`:

```clj
(require '[throttler.core :refer [throttle-chan throttle-fn]])
```

Create a throttled function. Here, we create a slower `+` that runs at 100
calls/second:

```clj
(def +# (throttle-fn + 100 :second))

(+# 1 1) ; => 2

(time (dotimes [_ 300] (+# 1 1))) ; "Elapsed time: 3399.865 msecs"
```

You can also create a bursty function by optionally supplying a burst rate.
Let's create a bursty multiply with an average rate of 100 calls/s and a burst rate of 1000 calls/s:

```clj
; goal: 100 calls/s on avg, bursts of up to 1000 calls/s
(def *# (throttle-fn * 100 1000 :second))

(*# 2 3) ; => 6

; First 1000 calls go through unthrottled, the 1001th needs to wait for about a second
(time (dotimes [_ 1001] (*# 2 3))) ; "Elapsed time: 1125.117 msecs" (889/second)

; Over a larg-ish number of invocations, the average rate is close to the goal
(time (dotimes [_ 10000] (*# 2 3))) ; "Elapsed time: 105041.395 msecs" (95/second)
```

## Throttling channels

For channels, simply swap `throttle-fn` with `throttle-chan`. It takes an
input channel that should be written to, and returns a throttled output channel
that should be read from:

```clj
(def in (chan 1))
(def slow-chan (throttle-chan in 1 :millisecond)) ; 1 msg/ms

(>!! in :token) ; => true
(<!! slow-chan) ; :token
```

This is functionally equivalent to `clojure.core.async/pipe`, except with a
controlled rate.

Here, we spin up a go thread that writes 5,000 messages to the `in` channel and
then closes it. On the main thread, we read from the throttled channel until we
get `nil`, which means the input channel was closed.

```clj
(go
 (dotimes [_ 5000]
  (>! in "hi!"))
 (close! in))

(time (while (not (nil? (<!! slow-chan))))) ; "Elapsed time: 5686.198 msecs" (0.9 msg/millisecond)
```

So it took 5.6 seconds to read 5000 messages, resulting in a rate of ~ 0.9
messages/millisecond.

## Throughput accuracy over a wide range of rates

While motivated by throttling to about 1-1000 messages/second, Throttler is
reasonably accurate over a wide range of rates. High rates are accurate with an
error margin of ~10% until we reach core.async's maximum possible pipe
throughput. On my laptop, this happens at about 50,000 messages/second.

Here's the result of running some [Criterium][2] benchmarks on channels
throttled at different rates.

```
Goal rate              Observed rate (mean)  Lower quantile (2.5%)  Upper quantile (95.5%)
---------------------  --------------------  ---------------------  ---------------------
      0.1  msg/s           0.1010 msgs/s          0.1008 msgs/s          0.1016 msgs/s
      1    msg/s           1.071  msgs/s          1.056  msgs/s          1.126  msgs/s
     10    msgs/s          10.77  msgs/s         10.67   msgs/s         11.33   msgs/s
    100    msgs/s          91.6   msgs/s         90.49   msgs/s         93.93   msgs/s
  1,000    msgs/s         892.2   msgs/s        886.0    msgs/s        907.5    msgs/s
 10,000    msgs/s       8,939     msgs/s      8,819      msgs/s      9,062      msgs/s
 30,000    msgs/s      26,571     msgs/s     25,970      msgs/s     27,184      msgs/s
100,000    msgs/s      49,958     msgs/s     47,602      msgs/s     50,981      msgs/s
∞ msgs/s (raw pipe)    48,657     msgs/s     47,663      msgs/s     49,550      msgs/s
```

The error stays below and at about 10% until we get close to the theoretical
maximum, which is the speed at which we can pipe messages through channels.

Same numbers apply to `throttle-fn`, as the implementation uses `throttle-chan`
under the hood.

[1]: http://en.wikipedia.org/wiki/Token_bucket
[2]: https://github.com/hugoduncan/criterium

## License

Copyright © 2014 Bruno Vecchi

Distributed under the Eclipse Public License, the same as Clojure.
