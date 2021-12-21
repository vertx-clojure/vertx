(defproject vertx-clojure/vertx "0.0.2-SNAPSHOT"
  :description "vert.x clojure style wrap package"
  :url "https://github.com/dG94Cg/vertx"
  :license {:name "MPL-2.0"
            :url "https://mozilla.org/MPL/2.0/"}
  :plugins [[lein-cloverage "1.0.13"]
            [lein-cljfmt "0.8.0"]]
  :managed-dependencies [[org.clojure/tools.logging "1.1.0"]
                 ;; https://mvnrepository.com/artifact/org.clojure/tools.namespace
                         [org.clojure/tools.namespace "1.1.0"]
                 ;; https://mvnrepository.com/artifact/mount/mount
                         [mount/mount "0.1.16"]
                 ;; https://mvnrepository.com/artifact/metosin/pohjavirta
                         [metosin/pohjavirta "0.0.1-alpha7"]
                 ;; https://mvnrepository.com/artifact/metosin/jsonista
                         [metosin/jsonista "0.3.4"]

                         [io.netty/netty-handler "4.1.68.Final"]
                         [io.netty/netty-handler-proxy "4.1.68.Final"]
                         [io.vertx/vertx-core "4.1.5"]
                         [org.clojure/spec.alpha "0.2.194"]
                         [io.netty/netty-codec-socks "4.1.68.Final"]
                         [io.netty/netty-codec-http "4.1.68.Final"]
                         [metosin/sieppari "0.0.0-alpha8"]
                         [io.vertx/vertx-web "4.1.5"]
                         [io.netty/netty-codec-dns "4.1.68.Final"]
                         [org.clojure/clojure "1.10.3"]
                         [org.clojure/core.specs.alpha "0.2.56"]
                         [io.netty/netty-transport "4.1.68.Final"]
                         [io.vertx/vertx-web-common "4.1.5"]
                         [io.netty/netty-common "4.1.68.Final"]
                         [io.netty/netty-buffer "4.1.68.Final"]
                         [io.netty/netty-resolver-dns "4.1.68.Final"]
                         [com.fasterxml.jackson.core/jackson-core "2.13.0"]
                         [meta-merge/meta-merge "1.0.0"]
                         [io.vertx/vertx-auth-common "4.1.5"]
                         [io.vertx/vertx-web-client "4.1.5"]
                         [io.vertx/vertx-bridge-common "4.1.5"]
                         [metosin/reitit-core "0.5.15"]
                         [funcool/promesa "5.0.0"]
                         [io.netty/netty-resolver "4.1.68.Final"]
                         [io.netty/netty-codec "4.1.68.Final"]
                         [io.netty/netty-codec-http2 "4.1.68.Final"]]
  :dependencies [[org.clojure/clojure]
                 [org.clojure/spec.alpha]
                 [org.clojure/tools.logging]
                 [io.vertx/vertx-web-client]
                 [io.vertx/vertx-web]
                 [io.vertx/vertx-core]
                 [funcool/promesa]
                 [metosin/reitit-core]
                 [metosin/sieppari]]
  :profiles {:test {:dependencies [[org.clojure/tools.namespace]
                                   [mount/mount]
                                   [metosin/pohjavirta]
                                   [metosin/jsonista]]}})
