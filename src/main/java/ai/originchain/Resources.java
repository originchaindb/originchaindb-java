package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Database operation namespaces. Obtain these from a reusable Client. */
public final class Resources {
    private Resources() {}

    public static final class Sql {
        private final Client client;

        Sql(Client client) {
            this.client = client;
        }

        private Map<String, Object> body(String statement, List<?> params) {
            if (statement == null || statement.isBlank())
                throw new IllegalArgumentException("SQL statement must not be empty");
            return Map.of("sql", statement, "params", params == null ? List.of() : params);
        }

        private SqlResult decode(JsonNode value) {
            var result = new SqlResult(value);
            if (result.failed())
                throw new OriginChainException("batch", 200, null, null, null, result);
            return result;
        }

        public SqlResult run(String statement) {
            return run(statement, List.of());
        }

        public SqlResult run(String statement, List<?> params) {
            return decode(client.request("POST", "sql", body(statement, params), Map.of(), null));
        }

        public SqlResult execute(String statement, List<?> params) {
            return run(statement, params);
        }

        public SqlResult query(String statement, List<?> params) {
            var result = run(statement, params);
            if (!result.kind().equals("select")) throw new OriginChainException("protocol");
            return result;
        }

        public CompletableFuture<SqlResult> runAsync(String statement, List<?> params) {
            var upstream =
                    client.requestAsync("POST", "sql", body(statement, params), Map.of(), null);
            var result = upstream.thenApply(this::decode);
            result.whenComplete(
                    (value, error) -> {
                        if (result.isCancelled()) upstream.cancel(true);
                    });
            return result;
        }
    }

    public static final class Schemas {
        private final Client client;

        Schemas(Client client) {
            this.client = client;
        }

        public JsonNode list() {
            return client.request("GET", "schemas", null, Map.of(), null);
        }

        public JsonNode get(String name) {
            return client.request("GET", Client.path("schemas", name), null, Map.of(), null);
        }

        public JsonNode register(String toml) {
            return client.request("POST", "schemas", null, Map.of(), toml);
        }
    }

    public static final class Rows {
        private final Client client;

        Rows(Client client) {
            this.client = client;
        }

        public JsonNode get(String schema, String key) {
            return client.request("GET", Client.path("rows", schema, key), null, Map.of(), null);
        }

        public JsonNode put(String schema, Map<String, ?> row) {
            return client.request("POST", Client.path("rows", schema), row, Map.of(), null);
        }

        public JsonNode putBatch(String schema, List<? extends Map<String, ?>> rows) {
            return client.request(
                    "POST", Client.path("rows", schema, "_batch"), rows, Map.of(), null);
        }
    }

    public record VectorSearchOptions(int k, String metric, Map<String, ?> filter) {
        public VectorSearchOptions() {
            this(10, "cosine", null);
        }

        public VectorSearchOptions {
            if (k <= 0 || metric == null || metric.isBlank())
                throw new IllegalArgumentException("Invalid vector search options");
        }
    }

    public static final class Vector {
        private final Client client;

        Vector(Client client) {
            this.client = client;
        }

        private void validate(double[] values) {
            if (values == null
                    || values.length == 0
                    || java.util.Arrays.stream(values).anyMatch(v -> !Double.isFinite(v)))
                throw new IllegalArgumentException("Vector must contain finite numbers");
        }

        public JsonNode put(String table, String id, double[] embedding, Map<String, ?> metadata) {
            validate(embedding);
            var body = new LinkedHashMap<String, Object>();
            body.put("id", id);
            body.put("embedding", embedding);
            body.put("dim", embedding.length);
            if (metadata != null) body.put("metadata", metadata);
            return client.request(
                    "POST", Client.path("vector", table, "put"), body, Map.of(), null);
        }

        public List<VectorHit> search(String table, double[] query) {
            return search(table, query, new VectorSearchOptions());
        }

        public List<VectorHit> search(String table, double[] query, VectorSearchOptions options) {
            validate(query);
            var body = new LinkedHashMap<String, Object>();
            body.put("query", query);
            body.put("dim", query.length);
            body.put("k", options.k());
            body.put("metric", options.metric());
            if (options.filter() != null) body.put("filter", options.filter());
            return VectorHit.decode(
                    client.request(
                            "POST", Client.path("vector", table, "topk"), body, Map.of(), null));
        }

        public JsonNode delete(String table, String id) {
            return client.request("DELETE", Client.path("vector", table, id), null, Map.of(), null);
        }
    }

    public record FtsSearchOptions(
            String mode, int k, boolean highlight, Integer fuzzy, List<String> facets) {
        public FtsSearchOptions() {
            this("bm25", 10, false, null, null);
        }

        public FtsSearchOptions {
            if (!List.of("bm25", "boolean", "phrase").contains(mode) || k <= 0)
                throw new IllegalArgumentException("Invalid full-text search options");
        }
    }

    public static final class Fts {
        private final Client client;

        Fts(Client client) {
            this.client = client;
        }

        public JsonNode index(String table, String field, String id, String text) {
            return client.request(
                    "POST",
                    Client.path("fts", table, field),
                    Map.of("doc_id", id, "text", text),
                    Map.of(),
                    null);
        }

        public FtsResult search(String table, String field, String query) {
            return search(table, field, query, new FtsSearchOptions());
        }

        public FtsResult search(
                String table, String field, String query, FtsSearchOptions options) {
            var params = new LinkedHashMap<String, String>();
            params.put("q", query);
            params.put("mode", options.mode());
            if (options.mode().equals("bm25")) params.put("k", "" + options.k());
            if (options.highlight()) params.put("highlight", "true");
            if (options.fuzzy() != null) params.put("fuzzy", options.fuzzy().toString());
            if (options.facets() != null) params.put("facets", String.join(",", options.facets()));
            return FtsResult.decode(
                    client.request("GET", Client.path("fts", table, field), null, params, null));
        }
    }

    public static final class Graph {
        private final Client client;

        Graph(Client client) {
            this.client = client;
        }

        public JsonNode query(String cypher, Map<String, ?> params) {
            if (cypher == null || cypher.isBlank())
                throw new IllegalArgumentException("Cypher statement must not be empty");
            return client.request(
                    "POST",
                    "cypher",
                    Map.of("cypher", cypher, "params", params == null ? Map.of() : params),
                    Map.of(),
                    null);
        }

        public JsonNode neighbors(String schema, String key, String relation) {
            return client.request(
                    "GET",
                    Client.path("graph", schema, "neighbors"),
                    null,
                    Map.of("pk", key, "rel", relation),
                    null);
        }

        public JsonNode bfs(String schema, String key, String relation, int maxDepth) {
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
            return client.request(
                    "GET",
                    Client.path("graph", schema, "bfs"),
                    null,
                    Map.of("pk", key, "rel", relation, "max_depth", "" + maxDepth),
                    null);
        }
    }
}
