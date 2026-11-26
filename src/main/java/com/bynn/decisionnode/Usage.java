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
 * @param imageTokens   the share of {@code inputTokens} that came from images, from a decision's
 *                      {@code x-decisionnode-image-tokens} header, as sent: never added to or taken
 *                      from another count, and never priced; null for a request without images (the
 *                      server sends the header only when the share is above 0), and when the header
 *                      is not a whole number from 0 to 2^31 - 1
 * @param videoTokens   the share of {@code inputTokens} that came from videos, from the
 *                      {@code x-decisionnode-video-tokens} header, read as {@code imageTokens} is;
 *                      null for a request without videos
 */
public record Usage(
        Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds, Long boosterTokens, Long boosters,
        Cost cost, Long imageTokens, Long videoTokens) {
    /** Token counts, video and booster usage and the cost, without the image and video shares. */
    public Usage(Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds, Long boosterTokens,
            Long boosters, Cost cost) {
        this(inputTokens, outputTokens, videoFrames, videoSeconds, boosterTokens, boosters, cost, null, null);
    }

    /** Token counts, video and booster usage, without the cost. */
    public Usage(Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds, Long boosterTokens,
            Long boosters) {
        this(inputTokens, outputTokens, videoFrames, videoSeconds, boosterTokens, boosters, null, null, null);
    }

    /** Token counts without video or booster usage. */
    public Usage(Long inputTokens, Long outputTokens) {
        this(inputTokens, outputTokens, null, null, null, null, null, null, null);
    }

    /** Token counts and video usage, without booster usage. */
    public Usage(Long inputTokens, Long outputTokens, Long videoFrames, Double videoSeconds) {
        this(inputTokens, outputTokens, videoFrames, videoSeconds, null, null, null, null, null);
    }
}
