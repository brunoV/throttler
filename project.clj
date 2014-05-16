(defproject throttler "1.0.0"
  :description "Control the throughput of function calls and core.async channels using the token bucket algorithm"
  :url "https://github.com/brunoV/throttler"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]]
  :profiles {:dev {:dependencies [[midje "1.5.1"]
                                  [criterium "0.4.3"]]}}
  :plugins [[com.keminglabs/cljx "0.3.2"]]
  :hooks [cljx.hooks]
  :source-paths ["gen/src"]
  :cljx {:builds [{:source-paths ["src"] :output-path "gen/src" :rules :clj}
                  {:source-paths ["src"] :output-path "gen/src" :rules :cljs}]})
