package ai.originchain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Uniform ranked, boolean and phrase results. Unranked hits have score zero. */
public record FtsResult(List<Hit> hits, JsonNode facets, JsonNode raw) {
    public record Hit(String docId, double score, JsonNode highlights, JsonNode raw) {}

    static FtsResult decode(JsonNode value) {
        JsonNode items = value.isObject() ? value.get("hits") : value;
        if (items == null || !items.isArray()) throw new OriginChainException("protocol");
        var hits = new ArrayList<Hit>();
        for (JsonNode item : items) {
            if (item.isTextual()) {
                hits.add(new Hit(item.textValue(), 0, null, null));
                continue;
            }
            if (!item.isObject() || !item.path("doc_id").isTextual())
                throw new OriginChainException("protocol");
            double score = 0;
            if (item.has("score")) {
                if (!item.get("score").isNumber()) throw new OriginChainException("protocol");
                score = item.get("score").doubleValue();
                if (!Double.isFinite(score)) throw new OriginChainException("protocol");
            }
            hits.add(
                    new Hit(
                            item.get("doc_id").textValue(),
                            score,
                            item.get("highlights"),
                            item.deepCopy()));
        }
        return new FtsResult(
                List.copyOf(hits), value.isObject() ? value.get("facets") : null, value.deepCopy());
    }
}
