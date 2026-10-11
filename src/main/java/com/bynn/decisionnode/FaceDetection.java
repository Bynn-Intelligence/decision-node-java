package com.bynn.decisionnode;

/**
 * One entry of a {@link FaceDetectionReading}'s detections.
 *
 * @param box        its box
 * @param confidence its {@code confidence}, as sent
 */
public record FaceDetection(FaceBox box, double confidence) {}
