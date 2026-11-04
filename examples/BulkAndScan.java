// Run against a development database; writes sdk_iteration.items.
import ai.originchain.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

public class BulkAndScan {
    static Iterable<Map<String, ?>> input() {
        return () ->
                new Iterator<>() {
                    int position;

                    public boolean hasNext() {
                        return position < 5;
                    }

                    public Map<String, ?> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        String id = "abcde".substring(position, ++position);
                        return Map.of("id", id, "name", "Item " + id);
                    }
                };
    }

    public static void main(String[] args) throws Exception {
        var db = Client.fromEnv();
        var schema = "sdk_iteration.items";
        db.schemas().register(Files.readString(Path.of("examples/items.toml")));
        var rows = input();
        // section:bulk
        try (var batches = db.rows().writeBatches(schema, rows, new BatchOptions(2, 1048576))) {
            batches.forEach(
                    batch ->
                            System.out.printf(
                                    "%d %d %d%n", batch.offset(), batch.count(), batch.inserted()));
        }
        // endsection
        // section:scan
        try (var scan = db.rows().scan(schema, "id", new PageOptions(2, null))) {
            scan.forEach(row -> System.out.println(row.get("id") + " " + row.get("name")));
        }
        // endsection
        // Save a checkpoint only after successfully processing the complete page.
        try (var pages = db.rows().pages(schema, "id", new PageOptions(2, "b"))) {
            pages.forEach(
                    page -> {
                        page.rows().forEach(row -> System.out.println("resumed " + row.get("id")));
                        System.out.println("next cursor " + page.nextCursor());
                    });
        }
    }
}
