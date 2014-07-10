(defproject throttler "2.0.0-SNAPSHOT"
  :description "Control the throughput of function calls and core.async channels using the token bucket algorithm"
  :url "https://github.com/brunoV/throttler"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]
                                  [criterium "0.4.3"]]}}
  :codox {:defaults {:doc/format :markdown}
          :src-dir-uri "http://github.com/brunov/throttler/blob/master/"
          :src-linenum-anchor-prefix "L"})
