package com.bynn.decisionnode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A batch's results, one {@link BatchResult} per request in upload order, read line by line from
 * the response as they are iterated: a large file is never held whole. Iterate once; close it
 * (or iterate to the end) to release the connection.
 *
 * <pre>{@code
 * try (BatchResults results = dn.batches().results(batchId)) {
 *     for (BatchResult r : results) { ... }
 * }
 * }</pre>
 */
public final class BatchResults implements Iterable<BatchResult>, AutoCloseable {
    private final Stream<String> lines;

    BatchResults(Stream<String> lines) {
        this.lines = lines;
    }

    @Override
    public Iterator<BatchResult> iterator() {
        return stream().iterator();
    }

    /** The results as a stream; closing it closes the response. */
    public Stream<BatchResult> stream() {
        return lines.filter(l -> !l.isBlank()).map(l -> {
            Object parsed = Json.readOrNull(l.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.Map<String, Object> m = Json.asMap(parsed);
            if (m == null) {
                throw new ServerError(new ErrorInfo(200, null, "a results line is not a JSON object", null, null, null,
                        java.util.Map.of()));
            }
            return new BatchResult(m);
        });
    }

    /** Reads every remaining line into a list. */
    public List<BatchResult> toList() {
        List<BatchResult> out = new ArrayList<>();
        try (Stream<BatchResult> s = stream()) {
            s.forEach(out::add);
        }
        return out;
    }

    @Override
    public void close() {
        lines.close();
    }
}
