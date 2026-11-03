package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** A vector match with a stable ID field and retained server metadata. */
public record VectorHit(String id, double score, JsonNode metadata, JsonNode raw) {
    static List<VectorHit> decode(JsonNode value) {
        if (!value.isArray()) throw new OriginChainException("protocol");
        var hits = new ArrayList<VectorHit>();
        for (JsonNode item : value) {
            JsonNode id = item.has("id") ? item.get("id") : item.get("vec_id");
            if (id == null
                    || !id.isTextual()
                    || !item.path("score").isNumber()
                    || !Double.isFinite(item.path("score").doubleValue()))
                throw new OriginChainException("protocol");
            hits.add(
                    new VectorHit(
                            id.textValue(),
                            item.get("score").doubleValue(),
                            item.get("metadata"),
                            item.deepCopy()));
        }
        return List.copyOf(hits);
    }
}
