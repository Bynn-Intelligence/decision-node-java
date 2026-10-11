package com.bynn.decisionnode;

/**
 * A face's box in a face reading: its corners exactly as sent, in pixels of the image as sent, or
 * fractions of the width and the height when every place question of the request asks for
 * normalized units.
 *
 * @param x1 the top-left corner, from the left
 * @param y1 the top-left corner, from the top
 * @param x2 the bottom-right corner, from the left
 * @param y2 the bottom-right corner, from the top
 */
public record FaceBox(double x1, double y1, double x2, double y2) {}
