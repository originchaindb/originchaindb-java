import ai.originchain.Client;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class Integration {
    public record Product(String id, String name) {}

    public static void main(String[] args) {
        // Configure your proxy or SSLContext on this application-owned HTTP client.
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var client =
                Client.builder(System.getenv("OC_URL"))
                        .tokenProvider(
                                () -> CompletableFuture.completedFuture(System.getenv("OC_TOKEN")))
                        .httpClient(http)
                        .onRequest(
                                event ->
                                        System.out.println(
                                                event.operation()
                                                        + " "
                                                        + event.status()
                                                        + " "
                                                        + event.duration()))
                        .build();
        var result = client.sql().run("SELECT $1 AS id, $2 AS name", List.of("p1", "Widget"));
        System.out.println(result.rowsAs(Product.class));
    }
}
