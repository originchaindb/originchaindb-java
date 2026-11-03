package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Complete SQL result, including partial batches and future server fields. */
public final class SqlResult {
    private final String kind;
    private final List<JsonNode> rows;
    private final List<String> columns;
    private final List<SqlResult> results;
    private final BigInteger rowsAffected;
    private final BigInteger rowsBuffered;
    private final boolean stoppedOnError;
    private final JsonNode raw;

    SqlResult(JsonNode value) {
        this(value, false);
    }

    private SqlResult(JsonNode value, boolean child) {
        if (value == null || !value.isObject()) throw new OriginChainException("protocol");
        raw = value.deepCopy();
        if (value.path("kind").isTextual()) kind = value.path("kind").textValue();
        else if (child && value.has("error")) kind = "error";
        else throw new OriginChainException("protocol");
        var rowList = new ArrayList<JsonNode>();
        for (JsonNode row : array(value, "rows")) {
            if (!row.isObject()) throw new OriginChainException("protocol");
            rowList.add(row.deepCopy());
        }
        rows = List.copyOf(rowList);
        var nested = new ArrayList<SqlResult>();
        for (JsonNode item : array(value, "results")) nested.add(new SqlResult(item, true));
        results = List.copyOf(nested);
        JsonNode cols = value.has("columns") ? value.get("columns") : value.get("returning");
        if (cols == null) columns = null;
        else {
            if (!cols.isArray()) throw new OriginChainException("protocol");
            var names = new ArrayList<String>();
            for (JsonNode col : cols) {
                if (!col.isTextual()) throw new OriginChainException("protocol");
                names.add(col.textValue());
            }
            columns = List.copyOf(names);
        }
        BigInteger count = count(value.get("rows_affected"));
        if (count == null && kind.equals("insert")) count = count(value.get("inserted"));
        if (count == null && kind.equals("select")) count = BigInteger.ZERO;
        rowsAffected = count;
        rowsBuffered = count(value.get("rows_buffered"));
        if (value.has("stopped_on_error") && !value.get("stopped_on_error").isBoolean())
            throw new OriginChainException("protocol");
        stoppedOnError = value.path("stopped_on_error").asBoolean(false);
    }

    private static Iterable<JsonNode> array(JsonNode value, String name) {
        if (!value.has(name)) return List.of();
        if (!value.get(name).isArray()) throw new OriginChainException("protocol");
        return value.get(name);
    }

    private static BigInteger count(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (!n.isIntegralNumber() || n.bigIntegerValue().signum() < 0)
            throw new OriginChainException("protocol");
        return n.bigIntegerValue();
    }

    boolean failed() {
        return stoppedOnError
                || kind.equals("error")
                || raw.has("error")
                || results.stream().anyMatch(SqlResult::failed);
    }

    public String kind() {
        return kind;
    }

    public List<JsonNode> rows() {
        return rows;
    }

    public List<String> columns() {
        return columns;
    }

    public JsonNode columnTypes() {
        return raw.get("column_types");
    }

    public BigInteger rowsAffected() {
        return rowsAffected;
    }

    public BigInteger rowsBuffered() {
        return rowsBuffered;
    }

    public List<SqlResult> results() {
        return results;
    }

    public boolean stoppedOnError() {
        return stoppedOnError;
    }

    public JsonNode raw() {
        return raw;
    }
}
