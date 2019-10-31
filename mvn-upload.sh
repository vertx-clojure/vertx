#!/bin/sh
mvn deploy:deploy-file -Dfile=target/vertx-clojure.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/
