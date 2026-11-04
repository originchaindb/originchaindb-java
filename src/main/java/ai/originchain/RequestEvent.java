package ai.originchain;

import java.time.Duration;

/**
 * Local transport metadata. Contains no URLs, table names, SQL, values, bodies, or credentials. A
 * null errorKind means HTTP success; result decoding may fail later.
 */
public record RequestEvent(
        String method,
        String operation,
        Duration duration,
        Integer status,
        String requestId,
        String errorKind) {
    static String operation(String path) {
        String first = path.split("/", 2)[0];
        return switch (first) {
            case "sql",
                            "rows",
                            "schemas",
                            "vector",
                            "fts",
                            "graph",
                            "cypher",
                            "usage",
                            "query-metrics" ->
                    first;
            default -> "other";
        };
    }
}
