package ai.originchain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Reusable database client with connection pooling and no SDK retries. */
public final class Client {
    public static final String VERSION = "1.0.0-rc.1";
    private static final int RESPONSE_LIMIT = 32 * 1024 * 1024;
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    private final String endpoint;
    private final String token;
    private final TokenProvider tokenProvider;
    private final Consumer<RequestEvent> observer;
    private final Duration timeout;
    private final HttpClient http;
    private final Resources.Sql sql = new Resources.Sql(this);
    private final Resources.Rows rows = new Resources.Rows(this);
    private final Resources.Schemas schemas = new Resources.Schemas(this);
    private final Resources.Vector vector = new Resources.Vector(this);
    private final Resources.Fts fts = new Resources.Fts(this);
    private final Resources.Graph graph = new Resources.Graph(this);

    public Client(String url, String token) {
        this(url, token, Duration.ofSeconds(60));
    }

    public Client(String url, String token, Duration timeout) {
        this(builder(url).token(token).timeout(timeout));
    }

    private Client(Builder options) {
        String url = options.url;
        String token = options.token;
        Duration timeout = options.timeout;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid database URL");
        }
        String host = uri.getHost();
        boolean loopback =
                "localhost".equals(host)
                        || "[::1]".equals(host)
                        || "::1".equals(host)
                        || host != null && host.matches("127\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}");
        if (host == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || url.chars().anyMatch(c -> c <= 32 || c == 127 || c == '\\')
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback))
            throw new IllegalArgumentException(
                    "Use the HTTPS database URL copied from Connect; HTTP is only allowed on"
                            + " loopback");
        if (options.provider == null && !validToken(token))
            throw new IllegalArgumentException(
                    "Token must be nonempty printable ASCII without spaces");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1)
            throw new IllegalArgumentException("Timeout must be positive");
        endpoint = url.replaceAll("/+$", "");
        this.token = token;
        this.tokenProvider = options.provider;
        this.observer = options.observer;
        this.timeout = timeout;
        if (options.http != null && options.http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Injected HttpClient must disable redirects");
        http =
                options.http != null
                        ? options.http
                        : HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .connectTimeout(
                                        timeout.compareTo(Duration.ofSeconds(10)) < 0
                                                ? timeout
                                                : Duration.ofSeconds(10))
                                .build();
    }

    /** Configure rotating credentials, local diagnostics, proxy and TLS through a native client. */
    public static Builder builder(String url) {
        return new Builder(url);
    }

    public static final class Builder {
        private final String url;
        private String token;
        private TokenProvider provider;
        private Consumer<RequestEvent> observer;
        private HttpClient http;
        private Duration timeout = Duration.ofSeconds(60);

        private Builder(String url) {
            this.url = url;
        }

        public Builder token(String token) {
            this.token = token;
            this.provider = null;
            return this;
        }

        public Builder tokenProvider(TokenProvider provider) {
            this.provider = Objects.requireNonNull(provider);
            this.token = null;
            return this;
        }

        public Builder onRequest(Consumer<RequestEvent> observer) {
            this.observer = observer;
            return this;
        }

        public Builder httpClient(HttpClient http) {
            this.http = Objects.requireNonNull(http);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Client build() {
            return new Client(this);
        }

        @Override
        public String toString() {
            return "OriginChainDB Client.Builder";
        }
    }

    /** Resolve the current token without blocking. Futures are canceled when the request ends. */
    @FunctionalInterface
    public interface TokenProvider {
        CompletionStage<String> getToken();
    }

    private static boolean validToken(String token) {
        return token != null
                && !token.isEmpty()
                && token.chars().noneMatch(c -> c <= 32 || c >= 127);
    }

    public static Client fromEnv() {
        return new Client(System.getenv("OC_URL"), System.getenv("OC_TOKEN"));
    }

    @Override
    public String toString() {
        return "OriginChainDB Client";
    }

    public Resources.Sql sql() {
        return sql;
    }

    public Resources.Rows rows() {
        return rows;
    }

    public Resources.Schemas schemas() {
        return schemas;
    }

    public Resources.Vector vector() {
        return vector;
    }

    public Resources.Fts fts() {
        return fts;
    }

    public Resources.Graph graph() {
        return graph;
    }

    public JsonNode usage() {
        return request("GET", "usage", null, Map.of(), null);
    }

    public JsonNode queryMetrics(int minutes, int bucketSeconds) {
        return request(
                "GET",
                "query-metrics",
                null,
                Map.of("minutes", "" + minutes, "bucket_seconds", "" + bucketSeconds),
                null);
    }

    static String path(String... parts) {
        return java.util.Arrays.stream(parts)
                .map(
                        part -> {
                            if (part == null
                                    || part.isEmpty()
                                    || part.equals(".")
                                    || part.equals("..")
                                    || part.chars().anyMatch(c -> c < 32 || c == 127))
                                throw new IllegalArgumentException("Invalid path name");
                            return encode(part);
                        })
                .collect(Collectors.joining("/"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
    }

    JsonNode request(
            String method, String path, Object body, Map<String, String> query, String text) {
        var pending = requestAsync(method, path, body, query, text);
        try {
            return pending.get();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new OriginChainException("canceled");
        } catch (ExecutionException e) {
            throw mapped(e.getCause());
        }
    }

    CompletableFuture<JsonNode> requestAsync(
            String method, String path, Object body, Map<String, String> query, String text) {
        long started = System.nanoTime();
        var observed = new Observation();
        var result = new CompletableFuture<JsonNode>();
        var active = new AtomicReference<CompletableFuture<JsonNode>>();
        CompletableFuture<String> credential;
        try {
            credential =
                    tokenProvider == null
                            ? CompletableFuture.completedFuture(token)
                            : tokenProvider.getToken().toCompletableFuture();
        } catch (RuntimeException error) {
            credential = CompletableFuture.failedFuture(new OriginChainException("credential"));
        }
        final var pendingCredential = credential;
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete(
                (value, error) -> {
                    pendingCredential.cancel(true);
                    var upstream = active.get();
                    if (error != null && upstream != null) upstream.cancel(true);
                    if (observer != null) {
                        String kind =
                                error == null
                                        ? null
                                        : ((OriginChainException) mapped(error)).kind();
                        var event =
                                new RequestEvent(
                                        method,
                                        RequestEvent.operation(path),
                                        Duration.ofNanos(System.nanoTime() - started),
                                        observed.status,
                                        observed.requestId,
                                        kind);
                        try {
                            observer.accept(event);
                        } catch (RuntimeException ignored) {
                            /* local observation must not alter results */
                        }
                    }
                });
        credential.whenComplete(
                (current, error) -> {
                    if (result.isDone()) return;
                    if (error != null || !validToken(current)) {
                        result.completeExceptionally(new OriginChainException("credential"));
                        return;
                    }
                    try {
                        var upstream =
                                requestHttpAsync(
                                        method, path, body, query, text, current, observed);
                        active.set(upstream);
                        upstream.whenComplete(
                                (value, failure) -> {
                                    if (failure == null) result.complete(value);
                                    else result.completeExceptionally(mapped(failure));
                                });
                        if (result.isDone()) upstream.cancel(true);
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    private static final class Observation {
        volatile Integer status;
        volatile String requestId;
    }

    private CompletableFuture<JsonNode> requestHttpAsync(
            String method,
            String path,
            Object body,
            Map<String, String> query,
            String text,
            String currentToken,
            Observation observed) {
        String address = endpoint + "/" + path;
        if (!query.isEmpty())
            address +=
                    "?"
                            + query.entrySet().stream()
                                    .map(
                                            pair ->
                                                    encode(pair.getKey())
                                                            + "="
                                                            + encode(pair.getValue()))
                                    .collect(Collectors.joining("&"));
        byte[] data;
        try {
            data =
                    text != null
                            ? text.getBytes(StandardCharsets.UTF_8)
                            : body == null ? null : JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Request body is not JSON serializable");
        }
        var builder =
                HttpRequest.newBuilder(URI.create(address))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + currentToken)
                        .header("Accept", "application/json")
                        .header("User-Agent", "originchaindb-java/" + VERSION)
                        .header("X-OC-Logical-Request-Id", UUID.randomUUID().toString());
        if (data != null)
            builder.header("Content-Type", text == null ? "application/json" : "text/plain");
        builder.method(
                method,
                data == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(data));
        var upstream = http.sendAsync(builder.build(), info -> new LimitedBody());
        var result = new CompletableFuture<JsonNode>();
        upstream.whenComplete(
                (response, error) -> {
                    if (error != null) {
                        result.completeExceptionally(mapped(error));
                        return;
                    }
                    try {
                        observed.status = response.statusCode();
                        observed.requestId =
                                response.headers().firstValue("x-oc-request-id").orElse(null);
                        result.complete(decode(response));
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                });
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete(
                (value, error) -> {
                    if (error != null) upstream.cancel(true);
                });
        return result;
    }

    private static RuntimeException mapped(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof OriginChainException e) return e;
        if (error instanceof TimeoutException
                || error instanceof java.net.http.HttpTimeoutException)
            return new OriginChainException("timeout");
        if (error instanceof CancellationException) return new OriginChainException("canceled");
        return new OriginChainException("transport");
    }

    private static JsonNode decode(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        JsonNode value;
        try {
            value =
                    response.body().length == 0
                            ? NullNode.instance
                            : JSON.readTree(response.body());
        } catch (Exception e) {
            if (status >= 200
                    && status < 300
                    && !response.headers()
                            .firstValue("content-type")
                            .orElse("")
                            .startsWith("text/")) throw new OriginChainException("protocol");
            value = TextNode.valueOf(new String(response.body(), StandardCharsets.UTF_8));
        }
        if (status < 200 || status >= 300) {
            String kind =
                    switch (status) {
                        case 401 -> "authentication";
                        case 403 -> "permission";
                        case 429 -> "rate_limit";
                        default -> "http";
                    };
            throw new OriginChainException(
                    kind,
                    status,
                    value,
                    response.headers().firstValue("x-oc-request-id").orElse(null),
                    response.headers().firstValue("retry-after").orElse(null),
                    null);
        }
        return value;
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long count;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) count += chunk.remaining();
            if (count > RESPONSE_LIMIT) {
                subscription.cancel();
                delegate.onError(new OriginChainException("protocol"));
            } else delegate.onNext(chunks);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
