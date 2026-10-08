package com.bynn.decisionnode;

/**
 * One event of a session's socket, in the order it arrived: a {@link Reply}, a
 * {@link FrameError}, or the {@link SessionClosed} that ends the stream. Frame errors are values,
 * never exceptions.
 */
public sealed interface SessionEvent permits Reply, FrameError, SessionClosed {}
