# API v1: Java

This reference describes the 1.0 source candidate's shared core and application
integration APIs. See the [installation and data examples](../../../README.md),
[migration guide](../../migration-v1.md), and [source](../../../src/main/java/ai/originchain)
for complete signatures, native types and language-specific helpers.

## Core operations

| Resource | Entry points |
| --- | --- |
| SQL | `sql().run / sql().query / sql().execute / sql().runAsync` |
| Schemas | `schemas().list / schemas().get / schemas().register` |
| Rows | `rows().get / rows().put / rows().putBatch` |
| Vectors | `vector().put / vector().search / vector().delete` |
| Full-text search | `fts().index / fts().search` |
| Graph | `graph().query / graph().neighbors / graph().bfs` |
| Usage and query metrics | `usage / queryMetrics` |

`run`/`RunAsync` accepts any supported SQL statement and returns the complete
statement envelope. `query`/`QueryAsync` requires a SELECT result. `execute` is
an alias for the complete result path. Bind values with positional parameters;
identifiers must come from trusted application code.

Rows use a schema name and primary key. Schema registration accepts TOML text.
Vector search returns IDs and scores; full-text search returns hits plus metadata
when supplied by the server. Graph operations use the relations declared in the
schema. The README has complete setup and query examples for each resource.

## Configure an application

`Client.builder(url).tokenProvider(provider).onRequest(observer).httpClient(http).build()`

Existing static-token constructors remain supported. Choose one credential source.
Copy the entire database address from Connect; no tenant path is inferred.

Inject an application-owned java.net.http.HttpClient. Its redirect policy must be NEVER or construction fails. Configure proxy, SSLContext and executor on that native client. Existing Client constructors are preserved.

The [complete integration example](../../../examples/Integration.java) uses a read-only
parameterized query, typed products, a token provider and a local observer. It
needs `OC_URL` and `OC_TOKEN`, but no schema setup or embedding service:

```sh
mvn -B -ntp package dependency:copy-dependencies
# Use ; instead of : in the classpath on Windows.
javac -cp "target/classes:target/dependency/*" -d target/examples examples/Integration.java
java -cp "target/examples:target/classes:target/dependency/*" Integration
```

## Rotating credentials

Client.TokenProvider returns CompletionStage<String>. Return promptly with a future; avoid blocking in getToken(). The SDK cancels pending lookup futures on request completion or cancellation.

The SDK validates every returned token and sends it unchanged as a bearer value.
No prefix is required. It does not implement a refresh protocol or automatically
retry after a 401/403. The next application call resolves the provider again.
Provider failures do not send an HTTP request and do not expose the provider's
exception message in the SDK's error text. Callbacks must not log credentials.

The example reads the process environment on each call. External secret rotation
does not update an existing process environment automatically; a deployed service
should return the current value from its own secret manager/cache.

## Typed rows

`result.rowsAs(Product.class)`

Jackson maps application records/classes. rowsAs(ObjectReader) accepts a reader with custom modules and coercion settings. Native Jackson rules apply. RowDecodeException.row() is zero-based; getCause() exposes the conversion failure.

Conversion makes no network request, preserves the raw result, and returns no
partial list on failure. It does not invent missing execution counts or coerce
exact decimal strings through floating point. Application-selected conversions
can change precision, so choose model types appropriate to your schema.

## Local request observation

The optional observer receives method, operation family, elapsed time
(`duration() (java.time.Duration)`), HTTP status, server request ID, and error category.
It receives no database URL, schema name, SQL text, query values, headers, response
body or credentials. It does not enable a telemetry exporter. Forward these
fields into your existing logs, metrics or tracing system when needed.

Observers run once per logical call. They should be short and nonblocking;
ordinary callback failures are isolated from the database result. Status is
absent (zero in Go) if no response headers arrived. A successful HTTP event may
be followed by a result-decoding, batch or model-conversion failure.

Common failure categories include authentication, permission, rate_limit,
timeout, canceled, transport, protocol, and credential. Native error types and
fields remain available: `OriginChainException with kind() credential; RowDecodeException.row()`. The README explains HTTP errors,
partial batches, and request IDs.

## Cancellation and ownership

sql().runAsync returns a CompletableFuture; cancellation propagates to token lookup and HTTP work. Blocking calls preserve thread interruption. The SDK timeout covers the overall asynchronous request.

Stopping the local wait does not prove that a server-side write was canceled.
Automatic SDK retries are disabled by default. Inspect a failed batch's partial
result before deciding how the application should recover.

## Compatibility checks

CI compiles/runs the native consumer, shared 24-request HTTP contract, nine-request
resilience contract, and applicable language tests. Those consumers exercise the
public core API and fail when a required method, type or behavior is removed.
The shared fixtures are pinned to the [organization contract](https://github.com/originchaindb/.github/tree/a47804a80fc5b31022d241dc814d905f6f78e723/sdk-contract/v1).
Changes to either fixture must be coordinated across all eight SDKs.

These checks cover the shared core. Changes to additional helpers also require
their native tests and API review. Fixtures model authentication failures; they
do not certify a deployed token lifecycle or an untested server version.

See [live verification](../../live-testing.md) for the manually dispatched database check.
