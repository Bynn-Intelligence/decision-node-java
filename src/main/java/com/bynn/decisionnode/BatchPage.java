package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One page of {@link Batches#list}: the key's batches, newest first. */
public final class BatchPage {
    private final Map<String, Object> raw;
    private final List<Batch> batches;

    BatchPage(Map<String, Object> raw) {
        this.raw = Collections.unmodifiableMap(raw);
        List<Batch> out = new ArrayList<>();
        List<Object> list = Json.asList(raw.get("batches"));
        if (list != null) {
            for (Object o : list) {
                Map<String, Object> m = Json.asMap(o);
                if (m != null) {
                    out.add(new Batch(m));
                }
            }
        }
        this.batches = Collections.unmodifiableList(out);
    }

    public List<Batch> batches() {
        return batches;
    }

    public Long offset() {
        return Json.asLong(raw.get("offset"));
    }

    public Long limit() {
        return Json.asLong(raw.get("limit"));
    }

    public Long total() {
        return Json.asLong(raw.get("total"));
    }

    /** The offset of the next page; null on the last page. */
    public Long nextOffset() {
        return Json.asLong(raw.get("next_offset"));
    }

    /** The page exactly as sent. */
    public Map<String, Object> raw() {
        return raw;
    }
}
