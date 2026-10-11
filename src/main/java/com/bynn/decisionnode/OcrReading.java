package com.bynn.decisionnode;

import java.util.Map;

/**
 * A reading of the {@code ocr} booster: the text one image shows, in reading order. A picture
 * that shows no text reads the empty string. With a {@code prompt} option (a task, an instruction
 * or a JSON schema, at most 2,000 characters, judged by the API), {@code text} is the answer to it.
 *
 * @param image     the id of the image read
 * @param text      the text read, as sent; {@code ""} when the picture shows none
 * @param truncated true when the reading was cut short
 * @param raw       the reading exactly as sent
 */
public record OcrReading(String image, String text, boolean truncated, Map<String, Object> raw) implements Reading {
    @Override
    public String booster() {
        return "ocr";
    }
}
