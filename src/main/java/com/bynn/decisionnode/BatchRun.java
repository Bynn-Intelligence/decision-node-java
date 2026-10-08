package com.bynn.decisionnode;

/**
 * The end of {@link DecisionNode#runBatch}: the ended batch and its results.
 *
 * @param batch   the batch as it ended
 * @param results its results, read line by line as they are iterated
 */
public record BatchRun(Batch batch, BatchResults results) {}
