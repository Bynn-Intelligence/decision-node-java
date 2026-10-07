package com.bynn.decisionnode;

import java.util.Map;

/**
 * A model listed by {@code GET /v1/models}.
 *
 * @param name        the model id to send, for example {@code decisionnode-1.0}
 * @param description what the model is for
 * @param releaseDate the release date as sent, for example {@code 2026-10-01}
 * @param limits      every other field of the entry: the published limits, such as the largest input
 * @param raw         the entry exactly as sent
 */
public record ModelInfo(String name, String description, String releaseDate, Map<String, Object> limits, Map<String, Object> raw) {}
