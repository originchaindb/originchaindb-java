import ai.originchain.Client;

import java.util.List;

class Example {
    public static void main(String[] args) {
        var db = Client.fromEnv();
        var result = db.sql().run("SELECT $1 AS value", List.of(7));
        System.out.println(result.rows());
    }
}
