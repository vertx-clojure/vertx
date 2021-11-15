import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import io.netty.util.concurrent.CompleteFuture;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

public class WorkerTest {
    public static void main(String[] args) {
        WorkerTest workerTest = new WorkerTest();
        workerTest.workerThreadTest();
    }
    public void workerThreadTest(){
        Vertx vertx = Vertx.vertx();
        Promise<Boolean> promise = Promise.promise();
        vertx.getOrCreateContext();
        vertx.runOnContext(v -> {
            vertx.getOrCreateContext().put("A", "we got");
            System.out.println(vertx.getOrCreateContext().get("A").toString());
        });
        vertx.executeBlocking(p -> {
            System.out.println("Blocking");
            p.complete(vertx.getOrCreateContext().get("A"));
        }).onSuccess(r -> {
            promise.complete(Objects.equals(r, vertx.getOrCreateContext().get("A")));
        }).onFailure(promise::fail);
        assert promise.future().toCompletionStage().toCompletableFuture().join();
        System.out.println(promise.future().toCompletionStage().toCompletableFuture().join());
        vertx.eventBus().consumer("a", msg -> {
            Map<String,Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", "ok");
            msg.reply(Json.encode(PersistentHashMap.create(res)));
        });
        vertx.eventBus().request("a", "me", msg -> {
            System.out.println(msg.result().body());
        });
      //  System.exit(0);
    }
}
