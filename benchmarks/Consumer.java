import ai.originchain.Client;

import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;

public class Consumer {
    static double query(Client client, String mode) {
        long start = System.nanoTime();
        var result = client.sql().query("bench:" + mode, List.of());
        if (result.rows().size() != (mode.equals("large") ? 2048 : 1))
            throw new AssertionError("Unexpected result size");
        return (System.nanoTime() - start) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        var client = Client.fromEnv();
        Map<String, Object> report = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) query(client, "warm");
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (String mode : List.of("sequential", "concurrent", "large")) {
                int count = mode.equals("sequential") ? 100 : mode.equals("concurrent") ? 64 : 10;
                var values = new ArrayList<Double>();
                long start = System.nanoTime();
                if (mode.equals("concurrent")) {
                    var tasks = new ArrayList<Callable<Double>>();
                    for (int i = 0; i < count; i++) tasks.add(() -> query(client, mode));
                    for (var task : pool.invokeAll(tasks)) values.add(task.get());
                } else for (int i = 0; i < count; i++) values.add(query(client, mode));
                report.put(mode + "_ms", values);
                report.put(mode + "_rps", count * 1e9 / (System.nanoTime() - start));
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println(
                "OC_BENCHMARK " + JsonMapper.builder().build().writeValueAsString(report));
    }
}
