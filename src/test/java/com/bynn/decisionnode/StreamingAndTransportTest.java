package com.bynn.decisionnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Batch results read as a stream with an idle timeout, and the socket's use of the client's transport. */
class StreamingAndTransportTest {
    private static final String LINE = "{\"custom_id\":\"r%d\",\"status\":200,\"body\":{\"model\":\"decisionnode-moe-0.9.0-rc1\","
            + "\"answers\":{\"q\":{\"type\":\"truth\",\"truth\":0.5}}},\"headers\":{}}\n";

    private HttpServer server;
    private String url;
    private volatile long stallMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 3; i++) {
                    out.write(String.format(LINE, i).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (stallMs > 0) {
                    Thread.sleep(stallMs);
                }
                out.write(String.format(LINE, 3).getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException | IOException e) {
                // the client went away
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DecisionNode client(Duration timeout) {
        return DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url).timeout(timeout).build();
    }

    @Test
    void resultsAreReadWhole() {
        List<BatchResult> all = client(Duration.ofSeconds(5)).batches().results("batch_0000000001").toList();
        assertEquals(4, all.size());
        assertEquals("r3", all.get(3).customId());
        assertEquals(0.5, all.get(0).decision().truth("q").truth());
    }

    @Test
    void aStalledStreamTimesOutWhileTheReaderWaits() {
        stallMs = 3000;
        try (BatchResults results = client(Duration.ofMillis(700)).batches().results("batch_0000000001")) {
            Iterator<BatchResult> it = results.iterator();
            for (int i = 0; i < 3; i++) {
                assertEquals("r" + i, it.next().customId());
            }
            long started = System.nanoTime();
            assertThrows(TimeoutError.class, it::hasNext);
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2500, "the idle timeout ended the read");
        }
    }

    @Test
    void aSlowReaderIsNotTimedOut() throws InterruptedException {
        stallMs = 0;
        try (BatchResults results = client(Duration.ofMillis(300)).batches().results("batch_0000000001")) {
            int n = 0;
            for (BatchResult r : results) {
                assertEquals("r" + n++, r.customId());
                Thread.sleep(500);
            }
            assertEquals(4, n);
        }
    }

    @Test
    void theSessionSocketUsesTheClientsProxySettings() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        List<URI> asked = new CopyOnWriteArrayList<>();
        ProxySelector recording = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                asked.add(uri);
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
        };
        DecisionNode dn = DecisionNode.builder().environment(k -> null).apiKey("dn_test").baseUrl(url)
                .httpClient(HttpClient.newBuilder().proxy(recording).build()).build();
        Session session = new Session(dn, Map.of("session_id", "ses_000000000000000000000000",
                "stream_url", "ws://127.0.0.1:" + closed + "/v1/sessions/ses_000000000000000000000000/stream",
                "questions", List.of("q")));
        assertThrows(DecisionNodeError.class, session::connect);
        assertTrue(asked.stream().anyMatch(u -> u.getPort() == closed), "the socket asked the client's proxy selector: " + asked);
    }
}
