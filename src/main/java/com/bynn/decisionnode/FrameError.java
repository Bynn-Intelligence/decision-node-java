package com.bynn.decisionnode;

import java.util.Map;

/**
 * A frame the session could not answer. The session stays open; send the next frame.
 *
 * @param seq          the frame's {@code seq}; null when the message could not be read as a frame
 * @param errorType    {@code api_usage_error}, {@code rate_limit_error}, {@code overloaded_error},
 *                     {@code api_error} or {@code refusal_error}
 * @param message      what went wrong, in words
 * @param category     for {@code refusal_error}: {@code harm} or {@code self_harm}; else null
 * @param retryAfterMs for {@code rate_limit_error} and {@code overloaded_error}: the wait the
 *                     server suggests, in milliseconds; else null
 * @param usage        for {@code refusal_error}: what the refused frame billed; else null
 * @param safety       for {@code refusal_error}: the safety check; else null
 * @param raw          the message exactly as sent
 */
public record FrameError(
        Long seq,
        String errorType,
        String message,
        String category,
        Long retryAfterMs,
        Usage usage,
        Safety safety,
        Map<String, Object> raw)
        implements SessionEvent {}
