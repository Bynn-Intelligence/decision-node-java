package com.bynn.decisionnode;

import java.util.Map;

/**
 * A model listed by {@code GET /v1/models}: a model of the catalogue, or one of the workspace's
 * fine-tuned models.
 *
 * @param name           the model id to send, for example {@code decisionnode-1.0}, or a fine-tuned
 *                       model's name, for example {@code decisionnode-1.0-flash@acme/fraud:3}
 * @param description    what the model is for
 * @param releaseDate    the release date as sent, for example {@code 2026-10-01}
 * @param fineTuned      true for a fine-tuned model; null for a catalogue entry
 * @param base           the pinned id of the model a fine-tuned model was trained from; null for a
 *                       catalogue entry
 * @param adapterVersion a fine-tuned model's version number; null for a catalogue entry
 * @param status         a fine-tuned model's status: {@code deployed} (it answers decisions),
 *                       {@code ready} (trained, not deployed), or any other value as sent; null
 *                       for a catalogue entry
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
        Map<String, Object> limits,
        Map<String, Object> raw) {

    /** A catalogue entry: no fine-tuned fields. */
    public ModelInfo(String name, String description, String releaseDate, Map<String, Object> limits, Map<String, Object> raw) {
        this(name, description, releaseDate, null, null, null, null, limits, raw);
    }
}
