package com.bynn.decisionnode;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * One long-lived feed through sessions: open, connect, send each reading, hand every reply on,
 * and reopen on the codes that call for it. The rules of {@link DecisionNode#supervise}.
 */
final class Supervisor {
    /** How long the end of the feed waits for each outstanding reply. */
    private static final Duration DRAIN = Duration.ofSeconds(10);

    private final DecisionNode client;
    private final SessionRequest request;
    private final Iterable<Frame> feed;
    private final Consumer<SessionEvent> onReply;

    private Session session;
    private Session previous;
    private SessionClosed last;
    private int pending;
    private int failures;
    private boolean answered;
    private boolean stopped;

    Supervisor(DecisionNode client, SessionRequest request, Iterable<Frame> feed, Consumer<SessionEvent> onReply) {
        if (request == null || feed == null || onReply == null) {
            throw new IllegalArgumentException("supervise needs a request, a feed and an onReply");
        }
        this.client = client;
        this.request = request;
        this.feed = feed;
        this.onReply = onReply;
    }

    SessionClosed run() {
        for (Frame reading : feed) {
            drain();
            deliver(reading);
            if (stopped) {
                return last;
            }
        }
        long deadline = System.nanoTime() + DRAIN.toNanos() * Math.max(1, pending);
        while (session != null && pending > 0) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            SessionEvent e;
            try {
                e = session.nextEvent(Duration.ofNanos(left));
            } catch (TimeoutError timeout) {
                break;
            }
            handle(e);
        }
        if (session != null) {
            session.end();
            session.close();
        }
        return last;
    }

    /**
     * Sends one reading, opening or reconnecting first when there is no session. A socket that
     * closed under the send is read as that close, and the reading goes to the next session.
     */
    private void deliver(Frame reading) {
        for (int tries = 0; ; tries++) {
            while (session == null) {
                if (last != null && !last.reopen() && last.code() != 1013) {
                    stopped = true; // a code that says stop: a fault to fix, not to retry
                    return;
                }
                if (last != null && last.code() == 1013) {
                    // The server was at its socket cap: the session is kept, connect to it again.
                    backoff();
                    Session again = previous.again();
                    last = null;
                    again.connect();
                    session = again;
                    continue;
                }
                if (last != null && last.code() != 4408 && last.code() != 4410) {
                    backoff();
                }
                Session opened = client.openSession(request);
                answered = false;
                opened.connect();
                session = opened;
                previous = opened;
                last = null;
            }
            try {
                session.send(reading);
                pending++;
                return;
            } catch (DecisionNodeError e) {
                if (tries > 0 || !awaitClose()) {
                    throw e;
                }
            }
        }
    }

    /** After a failed send: true when the socket's close arrives within a second. */
    private boolean awaitClose() {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (session != null) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                handle(session.nextEvent(Duration.ofNanos(left)));
            } catch (TimeoutError timeout) {
                return false;
            }
        }
        return true;
    }

    /** Hands on every event already arrived, without waiting. */
    private void drain() {
        while (session != null) {
            SessionEvent e = session.poll();
            if (e == null) {
                return;
            }
            handle(e);
        }
    }

    private void handle(SessionEvent e) {
        if (e instanceof SessionClosed) {
            last = (SessionClosed) e;
            session = null;
            pending = 0;
            return;
        }
        if (e instanceof Reply) {
            Reply r = (Reply) e;
            pending -= 1 + (r.dropped() == null ? 0 : r.dropped().size());
            answered = true;
        } else {
            pending -= 1;
        }
        pending = Math.max(0, pending);
        onReply.accept(e);
    }

    /** Capped backoff from 1 s to 30 s with jitter, reset after a session that answered frames. */
    private void backoff() {
        if (answered) {
            failures = 0;
        }
        double seconds = Math.min(30.0, Math.pow(2, failures)) * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
        failures++;
        Transport.await(client.transport().sleep(Math.round(seconds * 1000)));
    }
}
