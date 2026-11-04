package ai.originchain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "OC_RESILIENCE", matches = "1")
class ResilienceTest {
    record Product(long id, String amount) {}

    record Bad(byte id, String amount) {}

    @Test
    void failuresRotationMappingAndCancellation() throws Exception {
        String url = System.getenv("OC_URL"), token = System.getenv("OC_TOKEN");
        var client = new Client(url, token);
        String[] names = {"expired", "revoked", "forbidden", "limited"};
        int[] statuses = {401, 401, 403, 429};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            var error =
                    assertThrows(
                            OriginChainException.class, () -> client.sql().run("fault:" + name));
            assertEquals(statuses[i], error.status());
            assertEquals("84f92822-5837-432e-981a-7b24ad65e16c", error.requestId());
            if (name.equals("limited")) assertEquals("2", error.retryAfter());
        }
        assertThrows(OriginChainException.class, () -> client.sql().run("fault:interrupted"));
        var slow = new Client(url, token, Duration.ofMillis(150));
        assertEquals(
                "timeout",
                assertThrows(OriginChainException.class, () -> slow.sql().run("fault:timeout"))
                        .kind());
        var calls = new AtomicInteger();
        var events = new CopyOnWriteArrayList<RequestEvent>();
        var completed = new CountDownLatch(2);
        var rotating =
                Client.builder(url)
                        .tokenProvider(
                                () ->
                                        CompletableFuture.completedFuture(
                                                "rotate-" + calls.incrementAndGet()))
                        .httpClient(
                                HttpClient.newBuilder()
                                        .followRedirects(HttpClient.Redirect.NEVER)
                                        .build())
                        .onRequest(
                                event -> {
                                    events.add(event);
                                    completed.countDown();
                                })
                        .build();
        assertEquals(
                401,
                assertThrows(OriginChainException.class, () -> rotating.sql().run("fault:rotate"))
                        .status());
        assertEquals(1, calls.get());
        var result = rotating.sql().run("fault:rotate");
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertEquals(2, events.size());
        assertEquals("authentication", events.get(0).errorKind());
        assertNull(events.get(1).errorKind());
        assertEquals("sql", events.get(1).operation());
        assertTrue(events.get(1).duration().toNanos() > 0);
        assertFalse(events.toString().contains("rotate-"));
        assertFalse(events.toString().contains(url));
        var products = result.rowsAs(Product.class);
        assertEquals(9007199254740993L, products.get(0).id());
        assertEquals("1.00000000000000001", products.get(0).amount());
        assertEquals(
                0, assertThrows(RowDecodeException.class, () -> result.rowsAs(Bad.class)).row());
        var invalid =
                Client.builder(url)
                        .tokenProvider(() -> CompletableFuture.completedFuture("bad\nheader"))
                        .build();
        assertEquals(
                "credential",
                assertThrows(
                                OriginChainException.class,
                                () -> invalid.sql().run("fault:invalid-provider"))
                        .kind());
        var observed =
                Client.builder(url)
                        .token(token)
                        .onRequest(
                                event -> {
                                    throw new IllegalStateException("observer");
                                })
                        .build();
        assertEquals(1, observed.sql().run("fault:observer").rows().size());

        var lookup = new CompletableFuture<String>();
        var canceled = new CountDownLatch(1);
        var pending =
                Client.builder(url)
                        .tokenProvider(() -> lookup)
                        .onRequest(
                                event -> {
                                    if ("canceled".equals(event.errorKind())) canceled.countDown();
                                })
                        .build();
        var future = pending.sql().runAsync("not sent", List.of());
        future.cancel(true);
        assertTrue(canceled.await(3, TimeUnit.SECONDS));
        assertTrue(lookup.isCancelled());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Client.builder(url)
                                .token(token)
                                .httpClient(
                                        HttpClient.newBuilder()
                                                .followRedirects(HttpClient.Redirect.ALWAYS)
                                                .build())
                                .build());
    }
}
