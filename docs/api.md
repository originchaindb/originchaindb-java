# Using the client

Create `db` as shown in the [quickstart](../README.md). These independent examples
assume an existing `demo.items` schema and an appropriate search index.

```java
var result = db.sql().run("SELECT $1 AS value", java.util.List.of(7));
var row = db.rows().get("demo.items", "one");
var vectors = db.vector().search("demo.items", new double[]{1, 0});
var text = db.fts().search("demo.items", "body", "hello");
var graph = db.graph().query("MATCH (n) RETURN $name", java.util.Map.of("name", "one"));
```

## Results, errors, and lifetime

`SqlResult.rows()` contains Jackson `JsonNode` objects. `rowsAffected()` is
`BigInteger` or null. Vector records expose `id()`, `score()`, `metadata()`, and
`raw()`. Full-text results expose `hits()`, `facets()`, and `raw()`.

Catch `OriginChainException`; inspect `kind()`, `status()`, and `requestId()`.
`result()` contains a partial SQL result for batch errors.

Use `new Client(url, token, Duration.ofSeconds(10))` with `java.time.Duration`.
`db.sql().runAsync(statement, parameters)` returns a `CompletableFuture`.
Async failures follow the future's exception handling; cancel the future to
stop waiting locally. Reuse the client and its HTTP connection pool.

See the [shared behavior contract](contract.md) for parameter binding, batch
semantics, defaults, and retry behavior. The [contract consumer](../src/test/java/ai/originchain/ContractTest.java)
exercises the full common API against a local fixture.
