(defproject throttler "1.0.1"
  :description "Control the throughput of function calls and core.async channels using the token bucket algorithm"
  :url "https://github.com/brunoV/throttler"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.490"]]
  :profiles {:dev {:dependencies [[midje "1.9.8"]
                                  [criterium "0.4.5"]]
                   :plugins [[lein-midje "3.1.1"]]}})
