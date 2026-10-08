package com.bynn.decisionnode;

import java.net.URI;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An open session: the same questions answered about a stream of frames over one socket.
 *
 * <pre>{@code
 * Session s = dn.openSession(SessionRequest.builder()
 *     .state(Map.of("mission", "Survey the north field"))
 *     .question("abort", truth("Should the mission be aborted now?"))
 *     .build());
 * s.connect();
 * s.send(Frame.of(Map.of("battery", 0.31)));
 * for (SessionEvent e : s.replies()) {
 *     if (e instanceof Reply r) { r.answers().get("abort"); }
 * }
 * s.end();
 * }</pre>
 *
 * <p>{@link #send} never waits for a reply: replies, frame errors and the final
 * {@link SessionClosed} arrive in order through {@link #replies()}, {@link #nextEvent(Duration)}
 * or {@link #nextEventAsync()}. Each frame carries a {@code seq}, counted from 1 by the session
 * unless the frame gives its own. When frames arrive faster than they are answered the server
 * answers the newest and reports the ones it skipped in the next reply's {@code dropped}. Pings
 * are answered automatically. Methods are safe to call from several threads.
 */
public final class Session {
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final DecisionNode client;
    private final Map<String, Object> raw;
    private final Map<String, Answer> answers;
    private final AtomicLong seq = new AtomicLong();

    private final Object lock = new Object();
    private final ArrayDeque<SessionEvent> events = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<SessionEvent>> waiting = new ArrayDeque<>();
    private SessionClosed closed;
    private volatile WebSocket socket;
    /** The previous send: the socket takes one message at a time, in order. */
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

    Session(DecisionNode client, Map<String, Object> raw) {
        this(client, raw, 0);
    }

    private Session(DecisionNode client, Map<String, Object> raw, long lastSeq) {
        this.seq.set(lastSeq);
        this.client = client;
        this.raw = Collections.unmodifiableMap(raw);
        this.answers = Collections.unmodifiableMap(Responses.answers(raw.get("answers")));
    }

    /** The session id, {@code ses_...}. */
    public String sessionId() {
        return Responses.str(raw.get("session_id"));
    }

    /** The model version that answers this session. */
    public String model() {
        return Responses.str(raw.get("model"));
    }

    /** The socket URL. */
    public String streamUrl() {
        return Responses.str(raw.get("stream_url"));
    }

    /** When the session ends by itself, as sent ({@code 2026-10-06T09:35:00Z}). */
    public String expiresAt() {
        return Responses.str(raw.get("expires_at"));
    }

    /** The answers to the state alone, given at open, by question id. */
    public Map<String, Answer> answers() {
        return answers;
    }

    /** The tokens the open billed. */
    public Usage usage() {
        return Responses.usage(Json.asMap(raw.get("usage")));
    }

    /** The open response exactly as sent: every field, the limits and the window included. */
    public Map<String, Object> raw() {
        return raw;
    }

    /** The same session with a fresh socket, its frame counter carried on: for a connect again after 1013. */
    Session again() {
        return new Session(client, raw, seq.get());
    }

    // ------------------------------------------------------------------ connect

    /**
     * Opens the socket. The API key travels only in the handshake's {@code Authorization} header,
     * with the client's own headers. A session the server refuses at connect closes at once: its
     * {@link SessionClosed} is the first event.
     */
    public Session connect() {
        return Transport.await(connectAsync());
    }

    public CompletableFuture<Session> connectAsync() {
        Transport t = client.transport();
        URI uri;
        try {
            uri = URI.create(streamUrl());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new ServerError(new ErrorInfo(200, null,
                    "the session has no valid stream_url", null, null, raw, Map.of())));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("wss") && !(scheme.equals("ws") && DecisionNode.isLoopback(uri.getHost()))) {
            return CompletableFuture.failedFuture(new DecisionNodeError(
                    "the stream URL must be wss, so the key never travels unencrypted: " + uri.getScheme() + "://" + uri.getHost()));
        }
        if (t.apiKey == null || t.apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(new AuthenticationError(
                    "no API key: pass one to the client or set DECISIONNODE_API_KEY"));
        }
        WebSocket.Builder wb = t.http.newWebSocketBuilder()
                .connectTimeout(t.timeout)
                .header("Authorization", "Bearer " + t.apiKey)
                .header("User-Agent", Transport.USER_AGENT);
        for (Map.Entry<String, String> h : t.headers.entrySet()) {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            if (!RESTRICTED.contains(name) && !name.startsWith("sec-websocket-") && !name.equals("authorization")
                    && !name.equals("user-agent")) {
                wb.header(h.getKey(), h.getValue());
            }
        }
        return wb.buildAsync(uri, new Listener()).handle((ws, err) -> {
            if (err != null) {
                Throwable cause = Transport.unwrap(err);
                if (cause instanceof WebSocketHandshakeException) {
                    java.net.http.HttpResponse<?> r = ((WebSocketHandshakeException) cause).getResponse();
                    byte[] body = r.body() instanceof byte[] ? (byte[]) r.body() : null;
                    throw Responses.error(r.statusCode(), r.headers(), body);
                }
                throw t.transportError(cause);
            }
            socket = ws;
            return this;
        });
    }

    // ------------------------------------------------------------------ send

    /** Sends a frame holding this reading. See {@link #send(Frame)}. */
    public long send(Object frame) {
        return send(Frame.of(frame));
    }

    /**
     * Sends one frame and returns its {@code seq}. It waits only until the frame is written (an
     * image at or above the inline threshold is uploaded first), never for the reply.
     *
     * @throws BadRequestError before anything is sent, for an image the API would refuse or one
     *     given by URL
     */
    public long send(Frame frame) {
        CompletableFuture<Long> f = sendAsync(frame);
        return Transport.await(f);
    }

    /**
     * {@link #send(Frame)}: the {@code seq} is assigned and the frame queued at once, in call
     * order; the future completes when it is written.
     */
    public CompletableFuture<Long> sendAsync(Frame frame) {
        WebSocket ws = socket;
        if (ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("call connect() before send()"));
        }
        Object image = frame.image();
        try {
            // The frame's fixed rules, before anything is written: a refused frame takes no seq.
            checkFrame(frame);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        long n = frame.seq() != null ? frame.seq() : seq.incrementAndGet();
        CompletableFuture<Map<String, Object>> message;
        if (image instanceof Image) {
            message = client.uploads().wires(List.of(image), false, false).thenApply(w -> message(n, frame, w.wires().get(0)));
        } else {
            message = CompletableFuture.completedFuture(message(n, frame, image));
        }
        CompletableFuture<Long> done;
        synchronized (lock) {
            // One message at a time, in the order send was called: the socket refuses a second
            // text message while one is being written.
            done = sending.handle((v, e) -> null)
                    .thenCompose(v -> message)
                    .thenCompose(m -> ws.sendText(new String(Json.write(m), java.nio.charset.StandardCharsets.UTF_8), true))
                    .thenApply(w -> n);
            sending = done;
        }
        return done;
    }

    /**
     * Runs every check {@link #send(Frame)} runs before writing a frame and throws what it would
     * throw: the frame's fixed rules (its {@code seq}, a value or an image, the question ids of
     * this session, the nesting limit, the image rules, no URL image). Nothing is sent or
     * uploaded and no {@code seq} is used. Returns normally when the frame would be sent.
     *
     * @throws BadRequestError with {@code error_type} {@code api_usage_error} for a frame the
     *     server would refuse
     */
    public void checkFrame(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        LocalChecks.frame(frame, raw.get("questions"), client.limits(model()));
        Json.write(message(0, frame, frame.image() instanceof Image ? null : frame.image()));
    }

    private static Map<String, Object> message(long n, Frame frame, Object image) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", n);
        if (frame.frame() != null) {
            m.put("frame", frame.frame());
        }
        if (image != null) {
            m.put("image", image);
        }
        if (frame.questions() != null) {
            m.put("questions", frame.questions());
        }
        if (frame.reset() != null) {
            m.put("reset", frame.reset());
        }
        return m;
    }

    // ------------------------------------------------------------------ events

    /**
     * The next event, waiting up to {@code timeout}: a {@link Reply}, a {@link FrameError}, or the
     * {@link SessionClosed} that ends the stream (returned again on every later call).
     *
     * @throws DecisionNodeError when no event arrives in time
     */
    public SessionEvent nextEvent(Duration timeout) {
        CompletableFuture<SessionEvent> f = nextEventAsync();
        try {
            return f.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            synchronized (lock) {
                waiting.remove(f);
            }
            throw new TimeoutError("no session event within " + timeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            throw DecisionNode.asError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecisionNodeError("interrupted while waiting for a session event", e);
        }
    }

    /** The next event if one has arrived, else null; never waits. */
    SessionEvent poll() {
        synchronized (lock) {
            SessionEvent e = events.poll();
            return e != null ? e : closed;
        }
    }

    /** The next event, when it arrives. */
    public CompletableFuture<SessionEvent> nextEventAsync() {
        synchronized (lock) {
            SessionEvent e = events.poll();
            if (e != null) {
                return CompletableFuture.completedFuture(e);
            }
            if (closed != null) {
                return CompletableFuture.completedFuture(closed);
            }
            CompletableFuture<SessionEvent> f = new CompletableFuture<>();
            waiting.add(f);
            return f;
        }
    }

    /**
     * The events in arrival order, ending with the {@link SessionClosed}. Iterating blocks while
     * the next event is on its way.
     */
    public Iterable<SessionEvent> replies() {
        return () -> new Iterator<>() {
            private boolean done;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public SessionEvent next() {
                if (done) {
                    throw new NoSuchElementException();
                }
                SessionEvent e = Transport.await(nextEventAsync());
                done = e instanceof SessionClosed;
                return e;
            }
        };
    }

    /** The close, once one has arrived; null while the socket is open. */
    public SessionClosed closed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void push(SessionEvent e) {
        CompletableFuture<SessionEvent> f;
        synchronized (lock) {
            if (closed != null) {
                return;
            }
            if (e instanceof SessionClosed) {
                closed = (SessionClosed) e;
            }
            f = waiting.poll();
            if (f == null) {
                events.add(e);
                return;
            }
            if (e instanceof SessionClosed) {
                // Everyone waiting sees the close.
                List<CompletableFuture<SessionEvent>> rest = new ArrayList<>(waiting);
                waiting.clear();
                rest.forEach(w -> w.complete(e));
            }
        }
        f.complete(e);
    }

    static SessionEvent parse(Map<String, Object> m) {
        Map<String, Object> view = Collections.unmodifiableMap(m);
        Long seq = Json.asLong(m.get("seq"));
        Map<String, Object> error = Json.asMap(m.get("error"));
        if (error != null) {
            return new FrameError(seq, Responses.str(error.get("error_type")), Responses.str(error.get("message")),
                    Responses.str(error.get("category")), Json.asLong(m.get("retry_after_ms")),
                    m.get("usage") == null ? null : Responses.usage(Json.asMap(m.get("usage"))),
                    Safety.fromBody(m.get("safety")), view);
        }
        List<Long> dropped = null;
        List<Object> d = Json.asList(m.get("dropped"));
        if (d != null) {
            dropped = new ArrayList<>();
            for (Object o : d) {
                dropped.add(Json.asLong(o));
            }
            dropped = Collections.unmodifiableList(dropped);
        }
        return new Reply(seq, Collections.unmodifiableMap(Responses.answers(m.get("answers"))), dropped,
                Responses.usage(Json.asMap(m.get("usage"))), Json.asDouble(m.get("latency_ms")),
                Safety.fromBody(m.get("safety")), view);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                Map<String, Object> m = Json.asMap(Json.readOrNull(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (m != null) {
                    push(parse(m));
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            push(SessionClosed.of(code, reason));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            // A connection lost without a close frame.
            push(SessionClosed.of(1006, String.valueOf(error.getMessage())));
        }
    }

    // ------------------------------------------------------------------ end

    /**
     * Ends the session ({@code DELETE /v1/sessions/{id}}): the server closes the socket with
     * {@code 1000} and the frames in flight are not answered. A session that has already ended is
     * success too.
     *
     * @return the summary the server sent (frames answered and dropped, tokens billed), or null
     *     when the session had already ended
     */
    public Map<String, Object> end() {
        return Transport.await(endAsync());
    }

    public CompletableFuture<Map<String, Object>> endAsync() {
        return client.transport().json("DELETE", "/v1/sessions/" + sessionId(), null, Transport.Policy.DEFAULT)
                .handle((r, err) -> {
                    if (err == null) {
                        return r.object();
                    }
                    Throwable cause = Transport.unwrap(err);
                    if (cause instanceof NotFoundError) {
                        return null; // already ended: success
                    }
                    throw cause instanceof RuntimeException ? (RuntimeException) cause : DecisionNode.asError(cause);
                });
    }

    /** Closes the socket from this side with {@code 1000}. The session ends on the server. */
    public void close() {
        WebSocket ws = socket;
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
        }
    }

    @Override
    public String toString() {
        return "Session{" + sessionId() + "}";
    }
}
