package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class RowIteration {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RowIteration() {}

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Iteration interrupted");
    }

    private static <T> Stream<T> stream(Iterator<T> iterator) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }

    private static void finite(JsonNode node) {
        if (node.isFloatingPointNumber()
                && !node.isBigDecimal()
                && !Double.isFinite(node.doubleValue()))
            throw new IllegalArgumentException("Row contains a non-finite number");
        if (node.isContainerNode()) node.forEach(RowIteration::finite);
    }

    static Stream<BatchResult> writeBatches(
            Client client,
            String schema,
            Iterable<? extends Map<String, ?>> rows,
            BatchOptions options) {
        Objects.requireNonNull(options);
        Objects.requireNonNull(rows);
        String path = Client.path("rows", schema, "_batch");
        return stream(
                new Iterator<>() {
                    Iterator<? extends Map<String, ?>> input;
                    JsonNode pending;
                    long offset;
                    boolean failed;

                    private Iterator<? extends Map<String, ?>> input() {
                        if (input == null) input = rows.iterator();
                        return input;
                    }

                    public boolean hasNext() {
                        checkCancelled();
                        if (failed) return false;
                        try {
                            return pending != null || input().hasNext();
                        } catch (RuntimeException error) {
                            failed = true;
                            throw new BatchWriteException(offset, 0, "not_sent", error);
                        }
                    }

                    public BatchResult next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        var batch = new ArrayList<JsonNode>();
                        int size = 2;
                        try {
                            while (batch.size() < options.batchSize()) {
                                checkCancelled();
                                JsonNode row;
                                int length;
                                try {
                                    if (pending == null && !input().hasNext()) break;
                                    if (pending != null) {
                                        row = pending;
                                        pending = null;
                                    } else {
                                        Map<String, ?> item =
                                                Objects.requireNonNull(
                                                        input().next(),
                                                        "Each row must be an object");
                                        row = JSON.valueToTree(item);
                                        finite(row);
                                    }
                                    length = JSON.writeValueAsBytes(row).length;
                                    if (length + 2 > options.maxBytes())
                                        throw new IllegalArgumentException("Row exceeds maxBytes");
                                } catch (Exception error) {
                                    throw new BatchWriteException(
                                            offset, batch.size() + 1, "not_sent", error);
                                }
                                if (!batch.isEmpty() && size + 1 + length > options.maxBytes()) {
                                    pending = row;
                                    break;
                                }
                                size += length + (batch.isEmpty() ? 0 : 1);
                                batch.add(row);
                            }
                            checkCancelled();
                            try {
                                JsonNode value =
                                        client.request("POST", path, batch, Map.of(), null)
                                                .path("inserted");
                                if (!value.isIntegralNumber()
                                        || !value.canConvertToInt()
                                        || value.intValue() < 0
                                        || value.intValue() > batch.size())
                                    throw new OriginChainException("protocol");
                                var result =
                                        new BatchResult(offset, batch.size(), value.intValue());
                                offset += batch.size();
                                return result;
                            } catch (CancellationException error) {
                                throw error;
                            } catch (RuntimeException error) {
                                throw new BatchWriteException(
                                        offset, batch.size(), "unknown", error);
                            }
                        } catch (RuntimeException error) {
                            failed = true;
                            throw error;
                        }
                    }
                });
    }

    static Stream<RowPage> pages(Client client, String schema, String key, PageOptions options) {
        Objects.requireNonNull(options);
        var parts = schema.split("\\.", -1);
        if (parts.length > 2
                || !key.matches("[A-Za-z_][A-Za-z0-9_]*")
                || Arrays.stream(parts).anyMatch(p -> !p.matches("[A-Za-z_][A-Za-z0-9_]*")))
            throw new IllegalArgumentException("Use simple schema and key identifiers");
        String table =
                Arrays.stream(parts)
                        .map(p -> "\"" + p + "\"")
                        .collect(java.util.stream.Collectors.joining("."));
        return stream(
                new Iterator<>() {
                    String after = options.after();
                    RowPage pending;
                    boolean done;

                    public boolean hasNext() {
                        checkCancelled();
                        if (pending != null) return true;
                        if (done) return false;
                        try {
                            String sql =
                                    "SELECT * FROM "
                                            + table
                                            + (after == null ? "" : " WHERE \"" + key + "\" > $1")
                                            + " ORDER BY \""
                                            + key
                                            + "\" ASC LIMIT "
                                            + options.pageSize();
                            var rows =
                                    client.sql()
                                            .query(sql, after == null ? List.of() : List.of(after))
                                            .rows();
                            if (rows.size() > options.pageSize())
                                throw new OriginChainException("protocol");
                            String last = after;
                            for (JsonNode row : rows) {
                                var value = row.path(key);
                                if (!value.isTextual()
                                        || (last != null
                                                && Arrays.compareUnsigned(
                                                                value.textValue()
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8),
                                                                last.getBytes(
                                                                        StandardCharsets.UTF_8))
                                                        <= 0))
                                    throw new OriginChainException("protocol");
                                last = value.textValue();
                            }
                            done = rows.size() < options.pageSize();
                            if (rows.isEmpty()) return false;
                            after = done ? null : last;
                            pending = new RowPage(rows, after);
                            return true;
                        } catch (RuntimeException error) {
                            done = true;
                            throw error;
                        }
                    }

                    public RowPage next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        var result = pending;
                        pending = null;
                        return result;
                    }
                });
    }
}
