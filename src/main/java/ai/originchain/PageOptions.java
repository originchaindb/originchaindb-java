package ai.originchain;

/** Keyset continuation over a unique immutable string key. */
public record PageOptions(int pageSize, String after) {
    public PageOptions() {
        this(1000, null);
    }

    public PageOptions {
        if (pageSize < 1 || pageSize > 10000)
            throw new IllegalArgumentException("Invalid page size");
    }
}
