package com.bynn.decisionnode;

/**
 * One reading of a booster: what it found in one image. It is an {@link AgeReading}, an
 * {@link AiGeneratedReading}, a {@link FaceDetectionReading}, a {@link FaceRecognitionReading}, an
 * {@link OcrReading}, a {@link DepthReading} (Depth Vision), a {@link SegmentReading} (Segment
 * Vision), or an {@link UnknownReading} for a booster this SDK version does not know, or for a
 * reading whose fields are missing or of the wrong type.
 */
public sealed interface Reading
        permits AgeReading, AiGeneratedReading, FaceDetectionReading, FaceRecognitionReading, OcrReading, DepthReading,
                SegmentReading, UnknownReading {

    /** The name of the booster that read, for example {@code age}. */
    String booster();

    /** The reading exactly as the API sent it: a map, or for an {@link UnknownReading} any JSON value. */
    Object raw();
}
