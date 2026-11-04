package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** One keyset page. A null nextCursor marks completion. */
public record RowPage(List<JsonNode> rows, String nextCursor) {
    public RowPage {
        rows = List.copyOf(rows);
    }
}
