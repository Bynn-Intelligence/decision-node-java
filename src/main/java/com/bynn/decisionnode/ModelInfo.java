package com.bynn.decisionnode;

import java.util.Map;

/**
 * A model listed by {@code GET /v1/models}: a model of the catalogue, or one of the workspace's
 * fine-tuned models.
 *
 * @param name           the model id to send: a family ({@code decisionnode-moe},
 *                       {@code decisionnode-flash}), a pinned release
 *                       ({@code decisionnode-<family>-<version>}), or a fine-tuned model's name, for
 *                       example {@code decisionnode-flash@acme/fraud:3}
 * @param description    what the model is for
 * @param releaseDate    the release date as sent, for example {@code 2026-10-01}
 * @param fineTuned      true for a fine-tuned model; null for a catalogue entry
 * @param base           the pinned id of the model a fine-tuned model was trained from; null for a
 *                       catalogue entry
 * @param adapterVersion a fine-tuned model's version number; null for a catalogue entry
 * @param status         a fine-tuned model's status: {@code deployed} (it answers decisions),
 *                       {@code ready} (trained, not deployed), or any other value as sent; null
 *                       for a catalogue entry
 * @param pricePerMillionInput  the live price per million input tokens, in USD
 *                       ({@code price_per_million_input}), as sent; null when the entry has none
 * @param pricePerMillionOutput the price per million output tokens, in USD
 *                       ({@code price_per_million_output}), as sent; null when the entry has none
 * @param pricePerMillionImage  the price per million image tokens, in USD
 *                       ({@code price_per_million_image}), as sent; null when the entry has none
 * @param pricePerMillionVideo  the price per million video tokens, in USD
 *                       ({@code price_per_million_video}), as sent; null when the entry has none
 * @param batchPriceFactor the share of the live price a batch line of this model is billed
 *                       ({@code batch_price_factor}, for example 0.5); null on a model that takes no
 *                       batches, such as a fine-tuned model
 * @param limits         every other field of the entry: the published limits, such as the largest input
 * @param raw            the entry exactly as sent
 */
public record ModelInfo(
        String name,
        String description,
        String releaseDate,
        Boolean fineTuned,
        String base,
        Long adapterVersion,
        String status,
        Double pricePerMillionInput,
        Double pricePerMillionOutput,
        Double pricePerMillionImage,
        Double pricePerMillionVideo,
        Double batchPriceFactor,
        Map<String, Object> limits,
        Map<String, Object> raw) {

    /** An entry without prices. */
    public ModelInfo(String name, String description, String releaseDate, Boolean fineTuned, String base, Long adapterVersion,
            String status, Map<String, Object> limits, Map<String, Object> raw) {
        this(name, description, releaseDate, fineTuned, base, adapterVersion, status, null, null, null, null, null, limits, raw);
    }

    /** A catalogue entry: no fine-tuned fields and no prices. */
    public ModelInfo(String name, String description, String releaseDate, Map<String, Object> limits, Map<String, Object> raw) {
        this(name, description, releaseDate, null, null, null, null, limits, raw);
    }
}
