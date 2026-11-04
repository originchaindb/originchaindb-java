package ai.originchain;

/** One acknowledged input range. Earlier results remain valid after a later failure. */
public record BatchResult(long offset, int count, int inserted) {}
