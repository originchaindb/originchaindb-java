package ai.originchain;

/** Mapping failure. The message omits data; getCause exposes the native decoder error. */
public final class RowDecodeException extends RuntimeException {
    private final int row;

    public RowDecodeException(int row, Throwable cause) {
        super("Cannot decode SQL row " + row, cause);
        this.row = row;
    }

    /** Zero-based failing row index. */
    public int row() {
        return row;
    }
}
