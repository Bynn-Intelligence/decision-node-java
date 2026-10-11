package com.bynn.decisionnode;

/**
 * One face a {@link FaceDetectionReading} found.
 *
 * @param box        where the face is
 * @param confidence how sure the reading is that this is a face, 0 to 1
 */
public record FaceDetection(FaceBox box, double confidence) {}
