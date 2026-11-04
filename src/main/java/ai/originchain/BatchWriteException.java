package ai.originchain;

/** An unconfirmed range. Inspect unknown outcomes before retrying. */
public final class BatchWriteException extends RuntimeException {
    private final long offset;
    private final int count;
    private final String outcome;

    public BatchWriteException(long offset, int count, String outcome, Throwable cause) {
        super("Batch at input offset " + offset + " failed (" + outcome + ")", cause);
        this.offset = offset;
        this.count = count;
        this.outcome = outcome;
    }

    public long offset() {
        return offset;
    }

    public int count() {
        return count;
    }

    public String outcome() {
        return outcome;
    }
}
