package com.bynn.decisionnode;

/**
 * The tokens a decision used and, for a request with videos, what was read of them.
 *
 * @param inputTokens  tokens read; null when the response did not say
 * @param outputTokens tokens written; null when the response did not say
 * @param videoFrames  the frames read over all the videos; null for a request without videos
 * @param videoSeconds the seconds of the videos' windows read, summed, as sent; null for a
 *                     request without videos
 */
public record Usage(Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds) {
    /** Token counts without video usage. */
    public Usage(Long inputTokens, Long outputTokens) {
        this(inputTokens, outputTokens, null, null);
    }
}
