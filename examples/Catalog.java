import ai.originchain.Client;
import ai.originchain.OriginChainException;
import ai.originchain.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Catalog {
    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (OriginChainException error) {
            System.err.printf(
                    "Request failed: %s, HTTP %d, request ID %s%n",
                    error.kind(), error.status(), error.requestId());
            throw error;
        }
    }

    static void run() throws Exception {
        var db = Client.fromEnv();
        // section:setup
        var schema = "sdk_examples.products";
        db.schemas().register(Files.readString(Path.of("examples/products.toml")));
        // endsection

        // section:rows
        db.rows().put(schema, Map.of("id", "p2", "name", "Gadget", "description", "A red gadget"));
        db.rows()
                .put(
                        schema,
                        Map.of(
                                "id",
                                "p1",
                                "name",
                                "Widget",
                                "description",
                                "A small blue widget",
                                "related_to",
                                "p2"));
        var product = db.rows().get(schema, "p1");
        System.out.println(product);
        // endsection

        // section:sql
        var result =
                db.sql()
                        .run(
                                "SELECT id, name FROM sdk_examples.products WHERE id = $1",
                                List.of("p1"));
        for (var row : result.rows()) System.out.println(row.get("id") + " " + row.get("name"));
        // endsection

        // section:vector
        var embedding = new double[] {0.1, 0.2, 0.3, 0.4};
        db.vector().put(schema, "p1", embedding, Map.of("category", "tools"));
        var hits =
                db.vector()
                        .search(
                                schema,
                                embedding,
                                new Resources.VectorSearchOptions(5, "cosine", null));
        for (var hit : hits) System.out.println(hit.id() + " " + hit.score());
        // endsection

        // section:fts
        db.fts().index(schema, "description", "p1", "A small blue widget");
        var matches =
                db.fts()
                        .search(
                                schema,
                                "description",
                                "blue widget",
                                new Resources.FtsSearchOptions("bm25", 5, false, null, null));
        for (var hit : matches.hits()) System.out.println(hit.docId() + " " + hit.score());
        // endsection

        // section:graph
        var neighbors = db.graph().neighbors(schema, "p1", "related");
        System.out.println(neighbors);
        // endsection
    }
}
