package com.bynn.decisionnode;

import java.util.Map;

/**
 * An answer of a type this SDK version does not know. Its fields are in {@link #raw()}, so a new
 * question type on the server does not break an older client.
 *
 * @param type the answer's {@code type} as sent; null when it had none
 * @param raw  the answer exactly as sent
 */
public record UnknownAnswer(String type, Map<String, Object> raw) implements Answer {}
