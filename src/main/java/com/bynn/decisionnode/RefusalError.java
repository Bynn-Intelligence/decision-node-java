package com.bynn.decisionnode;

/**
 * HTTP 403 with the error type {@code refusal_error}: the request was refused under the usage
 * policy. Never retried.
 */
public class RefusalError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    private final String category;
    private final transient Safety safety;

    RefusalError(ErrorInfo info, String category, Safety safety) {
        super(info);
        this.category = category;
        this.safety = safety;
    }

    /** Why it was refused: {@code harm} or {@code self_harm}. */
    public String category() {
        return category;
    }

    /** The safety check of the refused request, or null when the response carried none. */
    public Safety safety() {
        return safety;
    }
}
