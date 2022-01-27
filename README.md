# vertx-clojure

A lightweight clojure adapter for vertx toolkit.

- **STATUS**: *alpha*, in design and prototyping phase.
- **AUDIENCE**: this is not a vertx documentation, this readme intends
  to explain only the clojure api

~~Example code on `resources/user.clj` file.~~

> view the test or check the below code


## Install

Using `deps.edn`:

```clojure
vertx-clojure/vertx {:mvn/version "0.0.3-SNAPSHOT"}
```

Using Leiningen:

```clojure
[vertx-clojure/vertx "0.0.3-SNAPSHOT"]
```

## User Guide


### Verticles

Verticles is the basic "unit of execution" in the vertx toolkit. The
concept is very simular to actors with the exception that a verticle
does not have a inbox, and verticles communicates with other verticles
or with the rest of the world using **eventbus**.

For create a verticle, you will need to create first a system instance
(a name that we give to the `Vertx` instance):

```clojure
(require '[vertx.core :as vc])

(def system (vc/system))
```

Then, you can proceed to create a verticle. A verticle concist on
three functions: `on-start`, `on-stop` and `on-error` (where the
`on-start` is mandatory the rest optional).

Lets define a dummy verticle that just prints hello world on start
callback:

```clojure
(defn on-start
  [ctx]
  (println "Hello world"))

(def dummy-verticle
  (vc/verticle {:on-start on-start}))
```

The `dummy-verticle` is a verticle factory, nothing is running at this
momment. For run the verticle we need to deploy it using the
previously created `system` instance:

```clojure
(vc/deploy! system dummy-verticle)
```

The `deploy!` return value is a `CompletionStage` so you can deref it
like a regular `Future` or use **funcool/promesa** for chain more
complex transformations. The return value implements `AutoCloseable`
that will allow to undeploy the verticle.

The `deploy!` function also accepts an additional parameter for
options, and at this momment it only accepts as single option:

- `:instances` - number of instances to launch of the same verticle.


### Event Bus

The **eventbus** is the central communication system for verticles. It
has different patterns of communication. On this documentation we will
cover the: `publish/subscribe` and `request/reply`.

Lets define a simple echo verticle:

```clojure
(require '[vertx.eventbus :as ve])

(defn on-message
  [msg]
  (:body msg))

(defn on-start
  [ctx]
  (vc/consumer ctx "test.echo" on-message))

(def echo-verticle
  (vc/verticle {:on-start on-start}))
```

And then, lets deploy 4 instances of it:

```clojure
(vc/deploy! system echo-verticle {:instances 4})
```

Now, depending on how you send the messages to the "test.echo" topic,
the message will be send to a single instance of will be broadcasted
to all verticle instances subscribed to it.

To send a message and expect a response we need to use the
`ve/request!` function:

```clojure
@(ve/request! system {:foo "bar"})
;; => #vertx.eventbus.Msg{:body {:foo "bar"}}
```

The return value of `on-message` callback will be used as a reply and
it can be any plain value or a `CompletionStage`.

When you want to send a message but you don't need the return value,
there is the `ve/send!` function. And finally, if you want to send a
message to all instances subscribed to a topic, you will need to use
the `ve/publish!` function.


### Http Server (vertx.http)

**STATUS**: pre-alpha: experimental & incomplete

This part will explain the low-level api for the http server. It is
intended to be used as a building block for a more higher-level api or
when you know that you exactly need for a performance sensitive
applications.

The `vertx.http` exposes two main functions `handler` and
`server`. Lets start creating a simple "hello world" http server:

```
(require '[vertx.http :as vh])

(defn hello-world-handler
  [req]
  {:status 200
   :body "Hello world\n"})

(defn on-start
  [ctx]
  (vh/server {:handler (vh/handler hello-world-handler)
              :port 2021}))

(->> (vc/verticle {:on-start on-start})
     (vc/deploy! system))
```

NOTE: you can start the server without creating a verticle but you
will loss the advantage of scaling (using verticle instances
parameter).

The `req` object is a plain map with the following keys:

- `:method` the HTTP method.
- `:path` the PATH of the requested URI.
- `:headers` a map with string lower-cased keys of headers.
- `:vertx.http/request` the underlying vertx request instance.
- `:vertx.http/response` the underlying vertx response instance.

And the response object to the ring response, it can contain
`:status`, `:body` and `:headers`.


**WARNING:** at this moment there are no way to obtain directly the
body of request using clojure api, this is in **design** phase and we
need to think how to expose it correctly without constraint too much
the user code (if you have suggestions, please open an issue).

**NOTE**: If you want completly bypass the clojure api, pass a vertx
`Handler` instance to server instead of using
`vertx.http/handler`. There is the `vertx.util/fn->handler` helper
that converts a plain clojure function into raw `Handler` instance.


### Web Server (vertx.web**

- Easy Web Provider

There is some easy http server at Node or Python, why can't we have one?

> I Know there is ring-jetty whatever im familiar with vertx more. lol

```clojure
(require '[vertx.web :refer [serve build-route assets]]
(require '[vertx.promise :refer [resolved])
(require '[my-api.user :refer [login route]])
(require '[vertx.core :refer [system]])
;; serve the http of api
(serve {:port 8080
        :route (build-route [["/api/hello" (fn [req] (resolved {"greet" "hello"}))]
                             ["/api/login" `login]])})
;; serve the http of html (static one)
(serve {:port 8888
        :route (assets "/var/run/html")})

;; use a specify Vertx to share same eventbus or the other vertx function
(def sys (system))
(serve {:port 8081
        :sys sys
        :route route})
```


**STATUS**: alpha

This part will explain the higher-level http/web server api. It is a
general purpose with more clojure friendly api. It uses
`reitit-core`for the routing and `sieppari` for interceptors.

Lets start with a complete example:

```clojure
(require '[vertx.http :as vh])
(require '[vertx.web :as vw])
(require '[vertx.web.interceptors :as vwi])

(defn hello-world-handler
  [req]
  {:status 200
   :body "Hello world!\n"})

(defn on-start
  [ctx]
  (let [routes [["/" {:interceptors [(vwi/cookies)]
                      :all hello-world-handler}]]
        handler (vw/handler ctx
                            (vw/assets "/static/*" {:root "resources/public/static"})
                            (vw/router routes))]
    (vh/server {:handler handler
                :port 2022})))

(->> (vc/verticle {:on-start on-start})
     (vc/deploy! system))
```

The routes are defined using `reitit-core` and the interceptors are
using `sieppari` as underlying implementation. The request object is
very similar to the one explained in `vertx.http`.

The main difference with `vertx.http` is that the handler is called
when the body is ready to be used and is available under `:body`
keyword on the request.

All additional features such that reading the query/form params,
parse/write cookies, cors and file uploads are provided with
interceptors as pluggable pieces:

- `vertx.web.interceptors/uploads` parses the vertx uploaded file data
  structure and expose it as clojure maps under `:uploads` key.
- `vertx.web.interceptors/params` parses the query string and form
  params in the body if the content-type is appropriate and exposes
  them under `:params`.
- `vertx.web.interceptors/cors` properly sets the CORS headers.
- `vertx.web.interceptors/cookies` handles the cookies reading from
  the request and cookies writing from the response.

## Actor

actor is mocked from the erlang-otp, but not actually same thing.

here is an example:

```clojure
(require [vertx.core :as vc]
         [vertx.eventbus :as bus])
(defn hot-code []
  (println "hot"))
(let [system (vc/system)
      record-score (fn [msg ctx suc err]
            ;; normally we use the JsonObject to carry the msg, but the clojure's map work too. here is a example of the JsonObject msg
            (let [
                   body (:body msg)
                   name (.get body "name")
                   score (.getLong body "score")
                   ]
                   (suc {
                         :merge {name score
                                 :sum (+ (:sum ctx) score)}
                         :reply true})
                     )
      )
      compute-fn (fn [msg {:keys [sum]} suc err]
         ;; here is a example that actor accept the clojure-map struct
         (let [ body   (:body msg)
                act    (:act body)
                modify (:value body)]
            (suc {:compute
                   ;; use the compute so that it should be run at event-loop. it's pretty useful if you need to get the data at worker-thread and modify the ctx with concurrent-protect
                    (fn [{:keys [sum] :as ctx}]
                      {:sum
                        (if (= :add act)
                          (+ sum modify)
                          (- sum modify))})
                  ;; the sum at here is not same as super one.
                  :reply (or sum 0)})
         ))
       actor (vc/actor {"add-score" record-score
                        "modify"    compute-fn
                        ;; use quote to hot-reload but it won't work with let bound var
                        "hot-code"  `hot-code}
                       {:on-start (fn [ctx] {:sum 0})})

      ]
      (vc/deploy! actor)
      (promesa.core/then
         (vertx.eventbus/request! "add-score" (io.vertx.core.json.JsonObject. {"name" "John" "score" 95}))
         (fn [r]
        (promesa.core/then (vertx.exentbus/request! "modify" {:act :add :value 0})
          (fn [reply]
            (println "score sum -> " (:body reply))))
      )))
(defn hot-code []
  (println "hot code"))
```

the actor fn will deal with arguments [msg ctx succ-res-fn err-res-fn], so that you can easily reply the msg at other thread like at `execute-blocking` clourse. if there is error throw out, that will be taken as you invoke the err-res-fn and fail the msg-reply.

- msg key -> `[:headers :body ^IFn :reply ^io.vertx.core.Message :self]`
- actor context, it's actually store at verticle-context's "state".
    you can get it by `(.get (vertx.core/current-context) "state")`

## Web build by Route

i like the erlang-otp(? no, elixir inspire me actually).
so here is some api like elixir-phoenix-style(no, no, no the style). it would build the route simply for you, i think is better than the origin vertx-clojure code.

```clojure
(ns user
  (:require [vertx.promise :as p]))
(defn login
  "login function, mock it"
  [request]
    (let [dto  (.toJson (:body request))]
      (if (and (-> dto (.getString "user") (= "admin"))
               (-> dto (.getString "passwd") (= "6-6-6-6-6-6"))
          (p/resolved {"code" 0 "data" {"user" "admin" "loginAt" (new java.util.Date)}})
          (p/resolved {"code" -1 "errorMsg" "fail to login, please check the name and password"})
      ))
    )
  )

(ns gallery
  (:require [vertx.promise :as p]))

;; i'm lazy to impl it
(declare upload-gallery)

(defn view-gallery
   "use the vertx file-system to read it, the vertx file-system will return a future, that it's why i use the future as return value to the request-handler"
   [request]
   (let [file-name (-> request (:param) (.get "filename"))
         vertx (.onwner (vc/currentContext))]
     (-> vertx (.filesystem)
       (.read file-name)
       ;; i'm not impl the set context-type here now, see the io.vertx.http.HttpServerResponse please
       (p/then (impl-context-type (:response request))))
   ))

;; these api require short uri. but in fact the router could extend it by :context see the below code please
(def sub-route [[:GET  "/gallery" view-gallery]
                ;; use quote to allow hot-reload
                [:POST "/gallery" 'upload-gallery]
                ;; the path-argument-extrater will set the file-name into param by read the uri. the regex will set true. the handler is just io.vertx.core.Handler<HttpServerRequest> like the vert.x own BodyHandler see io.vertx.ext.web.BodyHandler.
                {:path ["/gallery/(\\d*)"] :regex true :handler [path-argument-extrater] :respond view-gallery}
                ])

(ns server
  (:require [vertx.promise :as p]
            [vertx.core :as vc]
            [vertx.web :as web]))


;; INFO here is a handler example, it'll check the user login
(defn check-user-login
    "check the RoutingContext for further info"
    [req]
    (let [rtx (:routing-context req)
          user (.user rtx)]
          (if user
              (.next rtx)
              (.json rtx (io.vertx.core.json.JsonObject. {"code" -1 "msg" "not-login"})))))

;; that's a littler cool than the origin vert'x style isn't it?
(def router-handler
  (vertx.web/build-route
   [["/api/version" (fn [request] (vertx.promise/resolved {"code" 0 "data" "v0.0.1"}))]
    ;; use symbol to let user/login be able to hot-reload (it contain 200ms cache)
    [:POST "/api/user/login" `user/login]
    ;; context'll add the uri prefix into the real uri api
    {:context "/api/v2"
    ;; handler will deal as respond but require to invoke next
     :handler [check-user-login]
    ;; route add the sub route(not sub-router)
     :routes gallery/sub-route}]
   ))
;; create a vertx herer
(def s (vertx.core/system))
;; the server is just create a HttpServer
(vertx.web/server s
    ;; the handler will create a Router (Handler<HttpServerRequest>), and it accept vararg router handler, the handler shoudl be like (fn [router] ...). you can view it as source code
    {:handler (vertx.web/handler s router-handler ) :port 8095})
```


## License ##

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```
