# OriginChainDB for Java

The official Java client for SQL, vector search, full-text search, and graph queries.

[![CI](https://github.com/originchaindb/originchaindb-java/actions/workflows/ci.yml/badge.svg)](https://github.com/originchaindb/originchaindb-java/actions/workflows/ci.yml)

## Get started

Requires **Java 17+**. This is the unpublished 1.0 release candidate. Build from
source and pin a reviewed commit; registry publication is currently on hold.

```sh
git clone https://github.com/originchaindb/originchaindb-java.git
cd originchaindb-java
mvn install
```

Add the locally installed artifact to your application's `pom.xml`:

```xml
<dependency>
  <groupId>ai.originchain</groupId>
  <artifactId>originchaindb</artifactId>
  <version>1.0.0-rc.1</version>
</dependency>
```

Copy your database address and access token from Connect into `OC_URL` and
`OC_TOKEN`. Keep tokens in your server's environment or secret manager.

```java
import ai.originchain.Client;
import java.util.List;

class Example {
    public static void main(String[] args) {
        var db = Client.fromEnv();
        var result = db.sql().run("SELECT $1 AS value", List.of(7));
        System.out.println(result.rows());
    }
}
```

Reuse the client for your application's lifetime. Pass values as parameters;
do not interpolate application data into query text.

## One API family

Use `sql`, `schemas`, `rows`, `vector`, `fts`, and `graph` for the same database
concepts across every OriginChainDB SDK. Names use this language's normal casing.

- SQL results include returned rows, confirmed counts, metadata and batch results.
- Full-text search provides the same hit structure for ranked and unranked queries.
- Access tokens are sent as bearer credentials to the exact database address.
- Failed writes are not retried automatically.

[Using the client](docs/api.md) Â· [Behavior](docs/contract.md) Ã‚Â· [Contributing](CONTRIBUTING.md) Ã‚Â·
[Changes](CHANGELOG.md) Ã‚Â· [Security](SECURITY.md)

## Development

```sh
python tests/contract/run.py -- mvn -B verify
```

The HTTP contract runner requires Python 3.10+ for testing only. No database
credentials are needed for the offline suite. Native runtime and dependency
requirements above apply to applications.
