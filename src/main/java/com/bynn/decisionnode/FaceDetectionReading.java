package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of the {@code face_detection} booster, its fields as sent.
 *
 * @param image      the id of the image read
 * @param faces      the {@code faces} count as sent; it may be more than {@code detections} lists
 * @param detections the {@code detections} as sent, in order
 * @param raw        the reading exactly as sent
 */
public record FaceDetectionReading(String image, long faces, List<FaceDetection> detections, Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "face_detection";
    }
}
