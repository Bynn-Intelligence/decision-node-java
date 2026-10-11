package com.bynn.decisionnode;

/**
 * The tokens a decision used and, for a request with videos or boosters, what was read of them.
 *
 * @param inputTokens   tokens read; null when the response did not say
 * @param outputTokens  tokens written; null when the response did not say
 * @param videoFrames   the frames read over all the videos; null for a request without videos
 * @param videoSeconds  the seconds of the videos' windows read, summed, as sent; null for a
 *                      request without videos
 * @param boosterTokens the share of {@code inputTokens} the boosters' readings take; null for a
 *                      request without boosters
 * @param boosters      the booster calls billed; null for a request without boosters
 * @param cost          what the answer cost, as the API sent it; null when the answer does not say
 */
public record Usage(
        Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds, Long boosterTokens, Long boosters,
        Cost cost) {
    /** Token counts, video and booster usage, without the cost. */
    public Usage(Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds, Long boosterTokens,
            Long boosters) {
        this(inputTokens, outputTokens, videoFrames, videoSeconds, boosterTokens, boosters, null);
    }

    /** Token counts without video or booster usage. */
    public Usage(Long inputTokens, Long outputTokens) {
        this(inputTokens, outputTokens, null, null, null, null, null);
    }

    /** Token counts and video usage, without booster usage. */
    public Usage(Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds) {
        this(inputTokens, outputTokens, videoFrames, videoSeconds, null, null, null);
    }
}
