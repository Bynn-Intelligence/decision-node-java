package com.bynn.decisionnode;

import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A batch's results, one {@link BatchResult} per request in upload order. The whole results file
 * is read, however many lines it holds, line by line as the results are iterated, so a large file
 * is never held whole. The read fails with {@link TimeoutError} when no data arrives for the
 * client's timeout while you wait for the next line; a slow reader is never timed out. Iterate
 * once; close it (or iterate to the end) to release the connection.
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
        Iterator<String> source = lines.iterator();
        return new Iterator<>() {
            private BatchResult next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    String line;
                    try {
                        if (!source.hasNext()) {
                            return false;
                        }
                        line = source.next();
                    } catch (UncheckedIOException e) {
                        throw readError(e);
                    }
                    if (!line.isBlank()) {
                        next = parse(line);
                    }
                }
                return true;
            }

            @Override
            public BatchResult next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                BatchResult r = next;
                next = null;
                return r;
            }
        };
    }

    /** The results as a stream, read as it is consumed; closing it closes the response. */
    public Stream<BatchResult> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(lines::close);
    }

    /** Reads every remaining line into a list. */
    public List<BatchResult> toList() {
        List<BatchResult> out = new ArrayList<>();
        try {
            for (BatchResult r : this) {
                out.add(r);
            }
        } finally {
            close();
        }
        return out;
    }

    @Override
    public void close() {
        lines.close();
    }

    private static BatchResult parse(String line) {
        Map<String, Object> m = Json.asMap(Json.readOrNull(line.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (m == null) {
            throw new ServerError(new ErrorInfo(200, null, "a results line is not a JSON object", null, null, null, Map.of()));
        }
        return new BatchResult(m);
    }

    private static DecisionNodeError readError(UncheckedIOException e) {
        Throwable cause = e.getCause();
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) {
                return new TimeoutError("the results stopped arriving: " + t.getMessage(), e);
            }
        }
        return new ConnectionError("the results could not be read: " + cause, e);
    }
}
