package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;

/** Messages omit credentials and response data. Inspect diagnostics explicitly. */
public final class OriginChainException extends RuntimeException {
    private final String kind;
    private final int status;
    private final JsonNode body;
    private final String requestId;
    private final String retryAfter;
    private final SqlResult result;

    OriginChainException(String kind) {
        this(kind, 0, null, null, null, null);
    }

    OriginChainException(
            String kind,
            int status,
            JsonNode body,
            String requestId,
            String retryAfter,
            SqlResult result) {
        super("OriginChainDB " + kind + " error" + (status == 0 ? "" : " (HTTP " + status + ")"));
        this.kind = kind;
        this.status = status;
        this.body = body;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
        this.result = result;
    }

    public String kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public JsonNode body() {
        return body;
    }

    public String requestId() {
        return requestId;
    }

    public String retryAfter() {
        return retryAfter;
    }

    public SqlResult result() {
        return result;
    }
}
