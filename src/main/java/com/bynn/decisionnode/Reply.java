package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * The answers to one frame of a session.
 *
 * @param seq       the {@code seq} of the frame answered
 * @param answers   one typed answer per question asked, by question id, as in a {@link Decision}
 * @param dropped   the {@code seq} of each frame this one superseded since the previous reply
 *                  (latest wins), as received; null when none was
 * @param usage     the tokens this frame billed
 * @param latencyMs the server's time for this frame; null when absent
 * @param safety    the safety check on this frame; null when absent
 * @param raw       the message exactly as sent
 */
public record Reply(
        Long seq,
        Map<String, Answer> answers,
        List<Long> dropped,
        Usage usage,
        Double latencyMs,
        Safety safety,
        Map<String, Object> raw)
        implements SessionEvent {}
