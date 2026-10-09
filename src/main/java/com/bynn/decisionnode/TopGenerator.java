package com.bynn.decisionnode;

/**
 * The likeliest generator of an image, in an {@link AiGeneratedReading}.
 *
 * @param name        the generator's name, as sent
 * @param probability its probability, 0 to 1
 */
public record TopGenerator(String name, double probability) {}
