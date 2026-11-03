package ai.originchain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EnabledIfEnvironmentVariable(named = "OC_CONTRACT", matches = "1")
class ContractTest {
    @Test
    void completeHttpContract() {
        var client = Client.fromEnv();
        var result = client.sql().run("contract:select", Arrays.asList(7, "quote' / 雪", null));
        assertEquals(
                new BigInteger("9007199254740993"),
                result.rows().get(0).get("id").bigIntegerValue());
        assertEquals("1.00000000000000001", result.rows().get(0).get("amount").textValue());
        assertTrue(result.raw().get("future_field").get("kept").booleanValue());
        result = client.sql().run("contract:update");
        assertEquals(BigInteger.TWO, result.rowsAffected());
        assertEquals(BigInteger.valueOf(3), result.rowsBuffered());
        assertEquals(1, result.rows().size());
        assertEquals(List.of("id"), result.columns());
        result = client.sql().run("contract:unknown");
        assertEquals("future_statement", result.kind());
        assertNull(result.rowsAffected());
        for (String name :
                List.of(
                        "batch",
                        "unavailable",
                        "unauthorized",
                        "redirect",
                        "malformed",
                        "invalid-rows")) {
            var failure =
                    assertThrows(
                            OriginChainException.class, () -> client.sql().run("contract:" + name));
            if (name.equals("batch"))
                assertEquals(BigInteger.ONE, failure.result().results().get(0).rowsAffected());
            if (name.equals("unauthorized")) {
                assertEquals(401, failure.status());
                assertEquals("84f92822-5837-432e-981a-7b24ad65e16c", failure.requestId());
            }
            if (name.equals("unavailable")) {
                assertEquals(503, failure.status());
                assertEquals("0", failure.retryAfter());
            }
        }
        client.rows().get("demo/items", "a b?/%");
        var row = new HashMap<String, Object>();
        row.put("id", "one");
        row.put("nullable", null);
        client.rows().put("demo.items", row);
        client.rows().putBatch("demo.items", List.of(Map.of("id", "one"), Map.of("id", "two")));
        client.schemas().list();
        client.schemas().register("[schema]\nname=\"demo.items\"\n");
        client.vector().put("demo.items", "one", new double[] {1, 0}, Map.of("tag", "test"));
        assertEquals(
                List.of("one", "two"),
                client.vector().search("demo.items", new double[] {1, 0}).stream()
                        .map(VectorHit::id)
                        .toList());
        client.vector().delete("demo.items", "one");
        client.fts().index("demo.items", "body", "one", "hello world");
        assertEquals(
                "one", client.fts().search("demo.items", "body", "hello").hits().get(0).docId());
        var phrase =
                client.fts()
                        .search(
                                "demo.items",
                                "body",
                                "phrase",
                                new Resources.FtsSearchOptions("phrase", 10, false, null, null));
        assertEquals("two", phrase.hits().get(0).docId());
        assertEquals(0, phrase.hits().get(0).score());
        var enriched =
                client.fts()
                        .search(
                                "demo.items",
                                "body",
                                "highlight",
                                new Resources.FtsSearchOptions(
                                        "bm25", 10, true, null, List.of("tag")));
        assertNotNull(enriched.facets());
        assertNotNull(enriched.hits().get(0).highlights());
        client.graph().query("MATCH (n) RETURN $name", Map.of("name", "one"));
        client.graph().neighbors("demo.items", "one", "related");
        client.graph().bfs("demo.items", "one", "related", 5);
    }
}
