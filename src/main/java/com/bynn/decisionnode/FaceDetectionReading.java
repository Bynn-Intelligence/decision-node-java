package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/**
 * A reading of the {@code face_detection} booster: how many faces one image shows and where. A
 * picture with no face reads 0 faces and no detections. The booster takes no options.
 *
 * @param image      the id of the image read
 * @param faces      how many faces were found; it may be more than {@code detections} lists
 * @param detections the faces, by confidence as sent, each with its box and confidence
 * @param raw        the reading exactly as sent
 */
public record FaceDetectionReading(String image, long faces, List<FaceDetection> detections, Map<String, Object> raw)
        implements Reading {
    @Override
    public String booster() {
        return "face_detection";
    }
}
