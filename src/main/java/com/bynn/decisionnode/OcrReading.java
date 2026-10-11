package com.bynn.decisionnode;

import java.util.Map;

/**
 * A reading of the {@code ocr} booster, its fields as sent.
 *
 * @param image     the id of the image read
 * @param text      the {@code text} as sent, {@code ""} included
 * @param truncated the {@code truncated} flag as sent
 * @param raw       the reading exactly as sent
 */
public record OcrReading(String image, String text, boolean truncated, Map<String, Object> raw) implements Reading {
    @Override
    public String booster() {
        return "ocr";
    }
}
