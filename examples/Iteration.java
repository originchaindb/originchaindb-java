import ai.originchain.*;

import java.util.*;
import java.util.concurrent.CancellationException;

public class Iteration {
    static void check(boolean value) {
        if (!value) throw new AssertionError("Iteration assertion failed");
    }

    static Iterable<Map<String, ?>> source(List<Character> consumed) {
        return () ->
                new Iterator<>() {
                    int index;
                    final Map<String, Object> row = new HashMap<>();

                    public boolean hasNext() {
                        return index < 5;
                    }

                    public Map<String, ?> next() {
                        char id = "abcde".charAt(index++);
                        consumed.add(id);
                        row.put("id", Character.toString(id));
                        return row;
                    }
                };
    }

    public static void main(String[] args) {
        var db = Client.fromEnv();
        var consumed = new ArrayList<Character>();
        try (var stream =
                db.rows().writeBatches("bulk.ok", source(consumed), new BatchOptions(2, 1048576))) {
            check(consumed.isEmpty());
            var batches = stream.iterator();
            var first = batches.next();
            check(consumed.size() == 2 && first.equals(new BatchResult(0, 2, 2)));
            check(
                    batches.next().equals(new BatchResult(2, 2, 2))
                            && batches.next().equals(new BatchResult(4, 1, 1))
                            && !batches.hasNext());
        }
        for (String name : List.of("fail", "lost", "unknown")) {
            consumed = new ArrayList<>();
            try (var stream =
                    db.rows()
                            .writeBatches(
                                    "bulk." + name,
                                    source(consumed),
                                    new BatchOptions(2, 1048576))) {
                stream.toList();
                throw new AssertionError("Expected failure");
            } catch (BatchWriteException error) {
                check(
                        error.offset() == (name.equals("fail") ? 2 : 0)
                                && error.count() == 2
                                && error.outcome().equals("unknown")
                                && error.getCause() != null
                                && consumed.size() == (name.equals("fail") ? 4 : 2));
            }
        }
        var reports =
                db.rows()
                        .writeBatches(
                                "bulk.bytes",
                                List.of(
                                        Map.of("id", "a", "value", "x".repeat(20)),
                                        Map.of("id", "b", "value", "x".repeat(20)),
                                        Map.of("id", "c", "value", "x".repeat(20))),
                                new BatchOptions(1000, 64))
                        .toList();
        check(reports.stream().map(BatchResult::count).toList().equals(List.of(1, 1, 1)));
        consumed = new ArrayList<>();
        db.rows()
                .writeBatches("bulk.stop", source(consumed), new BatchOptions(2, 1048576))
                .limit(1)
                .toList();
        check(consumed.size() == 2);
        check(db.rows().writeBatches("bulk.never", List.of()).toList().isEmpty());
        try {
            db.rows()
                    .writeBatches(
                            "bulk.never", source(new ArrayList<>()), new BatchOptions(1000, 4))
                    .toList();
            throw new AssertionError("Expected failure");
        } catch (BatchWriteException error) {
            check(error.offset() == 0 && error.outcome().equals("not_sent"));
        }
        try {
            new BatchOptions(0, 1048576);
            throw new AssertionError("Expected failure");
        } catch (IllegalArgumentException error) {
        }
        Thread.currentThread().interrupt();
        try {
            db.rows().writeBatches("bulk.never", source(new ArrayList<>())).toList();
            throw new AssertionError("Expected cancellation");
        } catch (CancellationException error) {
        } finally {
            Thread.interrupted();
        }
        check(
                db.rows()
                        .scan("scan.items", "id", new PageOptions(2, null))
                        .map(row -> row.path("id").textValue())
                        .toList()
                        .equals(List.of("a", "b", "c", "d", "e")));
        check(
                db.rows()
                        .scan("scan.stop", "id", new PageOptions(2, null))
                        .limit(1)
                        .findFirst()
                        .orElseThrow()
                        .path("id")
                        .asText()
                        .equals("a"));
        var pages = db.rows().pages("scan.resume", "id", new PageOptions(2, "b")).toList();
        check(
                pages.size() == 2
                        && pages.get(0).nextCursor().equals("d")
                        && pages.get(1).nextCursor() == null);
        for (String name : List.of("bad", "numeric", "oversize")) {
            try {
                db.rows().pages("scan." + name, "id", new PageOptions(2, null)).toList();
                throw new AssertionError("Expected failure");
            } catch (OriginChainException error) {
                check(error.kind().equals("protocol"));
            }
        }
        check(db.rows().scan("scan.empty", "id", new PageOptions(2, null)).toList().isEmpty());
        try {
            db.rows().scan("scan.items;DELETE", "id").toList();
            throw new AssertionError("Expected failure");
        } catch (IllegalArgumentException error) {
        }
        System.out.println("Java iteration assertions passed");
    }
}
