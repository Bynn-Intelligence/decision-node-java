package com.bynn.decisionnode;

import java.util.Map;

/**
 * A reading of the {@code ocr} booster: the text one image shows, all of it by default, or what
 * the {@code prompt} option asks for (a formula in LaTeX, a table in HTML or Markdown, the fields of
 * a JSON schema the prompt gives; at most 2,000 characters).
 *
 * @param image     the id of the image read
 * @param text      the text read, as sent; {@code ""} when the picture shows none
 * @param truncated true when the reading was too long for the request and the model read it cut at
 *                  its end; {@code text} stays whole
 * @param raw       the reading exactly as sent
 */
public record OcrReading(String image, String text, boolean truncated, Map<String, Object> raw) implements Reading {
    @Override
    public String booster() {
        return "ocr";
    }
}
