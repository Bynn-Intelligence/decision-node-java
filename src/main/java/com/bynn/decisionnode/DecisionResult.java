package com.bynn.decisionnode;

/**
 * One result of {@link DecisionNode#decideMany}: the {@link Decision}, or the error that request
 * ended with.
 */
public final class DecisionResult {
    private final Decision decision;
    private final DecisionNodeError error;

    private DecisionResult(Decision decision, DecisionNodeError error) {
        this.decision = decision;
        this.error = error;
    }

    static DecisionResult of(Decision d) {
        return new DecisionResult(d, null);
    }

    static DecisionResult of(DecisionNodeError e) {
        return new DecisionResult(null, e);
    }

    /** True when the request returned a decision. */
    public boolean isSuccess() {
        return error == null;
    }

    /** The decision, or null when the request failed. */
    public Decision decision() {
        return decision;
    }

    /** The error, or null when the request succeeded. */
    public DecisionNodeError error() {
        return error;
    }

    /** The decision, or throws the error. */
    public Decision getOrThrow() {
        if (error != null) {
            throw error;
        }
        return decision;
    }

    @Override
    public String toString() {
        return error != null ? "DecisionResult{error=" + error + "}" : "DecisionResult{" + decision + "}";
    }
}
