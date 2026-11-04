package ai.originchain;

/** Bounds for one atomic JSON request. No automatic replay. */
public record BatchOptions(int batchSize, int maxBytes) {
    public BatchOptions() {
        this(1000, 1024 * 1024);
    }

    public BatchOptions {
        if (batchSize < 1 || batchSize > 10000 || maxBytes < 2 || maxBytes > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid batch bounds");
    }
}
