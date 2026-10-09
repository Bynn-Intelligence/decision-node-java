package com.bynn.decisionnode;

import java.util.Map;

/**
 * A reading of the {@code ai_generated} booster: how likely one image was made by an image
 * generator, and whether the file carries Content Credentials (C2PA).
 *
 * @param image        the id of the image read
 * @param generated    the probability that the image was made by a generator, 0 to 1
 * @param confidence   how sure the reading is, 0 to 1
 * @param method       how it was read: {@code inference} (from the pixels), {@code c2pa} (the
 *                     file's Content Credentials say so), or any other value as sent
 * @param topGenerator the likeliest generator; null unless the reading says the image is likely
 *                     generated, and on every {@code c2pa} reading
 * @param c2paSigner   who signed the file's Content Credentials; null when it has none
 * @param c2pa         {@code untrusted} when the file's Content Credentials validate but their
 *                     signer is not trusted, any other value as sent, or null when the reading has
 *                     none
 * @param raw          the reading exactly as sent
 */
public record AiGeneratedReading(
        String image,
        double generated,
        double confidence,
        String method,
        TopGenerator topGenerator,
        String c2paSigner,
        String c2pa,
        Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "ai_generated";
    }
}
