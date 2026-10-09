package com.bynn.decisionnode;

/**
 * How a fallback answer came about: the API answered a request for the full model from Flash,
 * because no worker of the full model could take it at once. Ask for no fallback with
 * {@link DecideRequest.Builder#fallback(Boolean) fallback(false)}.
 *
 * @param servedModel    the model that answered, from {@code x-decisionnode-fallback}
 * @param requestedModel the model the request asked for, from
 *                       {@code x-decisionnode-requested-model}; null when the header is absent
 */
public record Fallback(String servedModel, String requestedModel) {}
