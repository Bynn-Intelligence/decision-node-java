package com.bynn.decisionnode;

import java.util.Set;

/**
 * The end of a session's socket: the close code and reason. A connection lost without a close
 * frame is {@code 1006}.
 *
 * @param code   the WebSocket close code
 * @param reason the close reason, short text for logs; branch on the code
 * @param reopen true when the code calls for opening a new session with the same request
 *               ({@code 1001}, {@code 1006}, {@code 1011}, {@code 4404}, {@code 4408},
 *               {@code 4409}, {@code 4410}). {@code 1013} is false: the session is kept, so
 *               connect to it again after a backoff
 */
public record SessionClosed(int code, String reason, boolean reopen) implements SessionEvent {
    /** The close codes after which a new session is opened with the same request. */
    public static final Set<Integer> REOPEN_CODES = Set.of(1001, 1006, 1011, 4404, 4408, 4409, 4410);

    /** A close with this code and reason; {@code reopen} follows from the code. */
    public static SessionClosed of(int code, String reason) {
        return new SessionClosed(code, reason == null ? "" : reason, REOPEN_CODES.contains(code));
    }
}
