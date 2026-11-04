// Private integration worker. Credentials arrive over stdin and are never logged.
import ai.originchain.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;

public class EngineCompatibility {
    static void check(boolean value) {
        if (!value) throw new AssertionError("Engine compatibility assertion failed");
    }

    public static void main(String[] args) throws Exception {
        var current = new AtomicReference<>("unset");
        var calls = new AtomicInteger();
        var db =
                Client.builder(System.getenv("OC_URL"))
                        .tokenProvider(
                                () -> {
                                    calls.incrementAndGet();
                                    return CompletableFuture.completedFuture(current.get());
                                })
                        .build();
        var json = JsonMapper.builder().build();
        try (var input =
                new BufferedReader(
                        new InputStreamReader(
                                System.in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                var cmd = json.readTree(line);
                current.set(cmd.path("token").asText());
                int before = calls.get(), status = 200;
                try {
                    switch (cmd.path("action").asText()) {
                        case "query" -> {
                            var params = new ArrayList<JsonNode>();
                            if (cmd.has("params")) cmd.path("params").forEach(params::add);
                            var result = db.sql().run(cmd.path("sql").asText(), params);
                            check(
                                    cmd.path("status").asInt() == 200
                                            && result.rows().size() == cmd.path("rows").asInt());
                        }
                        case "bulk" -> {
                            var rows = new ArrayList<Map<String, ?>>();
                            for (int i = 0; i < 5; i++)
                                rows.add(
                                        Map.of(
                                                "id",
                                                Character.toString("abcde".charAt(i)),
                                                "value",
                                                i));
                            try (var stream =
                                    db.rows()
                                            .writeBatches(
                                                    cmd.path("schema").asText(),
                                                    rows,
                                                    new BatchOptions(2, 1048576))) {
                                check(
                                        stream.toList()
                                                .equals(
                                                        List.of(
                                                                new BatchResult(0, 2, 2),
                                                                new BatchResult(2, 2, 2),
                                                                new BatchResult(4, 1, 1))));
                            }
                        }
                        case "scan" -> {
                            try (var stream =
                                    db.rows()
                                            .scan(
                                                    cmd.path("schema").asText(),
                                                    "id",
                                                    new PageOptions(2, null))) {
                                check(
                                        stream.map(row -> row.path("id").asText())
                                                .toList()
                                                .equals(List.of("a", "b", "c", "d", "e")));
                            }
                        }
                        default -> throw new AssertionError("Unknown compatibility action");
                    }
                } catch (OriginChainException error) {
                    status = error.status();
                    check(
                            cmd.path("status").asInt() != 200
                                    && status == cmd.path("status").asInt());
                }
                check(calls.get() - before == cmd.path("requests").asInt());
                System.out.println(
                        json.writeValueAsString(
                                Map.of(
                                        "ok",
                                        true,
                                        "status",
                                        status,
                                        "requests",
                                        calls.get() - before)));
                System.out.flush();
            }
        }
    }
}
