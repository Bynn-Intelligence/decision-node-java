package com.bynn.decisionnode;

import java.util.List;

/** HTTP 422: the request body does not have the shape the API expects. Not retried. */
public class ValidationError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    private final transient List<Object> entries;

    ValidationError(ErrorInfo info, List<Object> entries) {
        super(info);
        this.entries = entries;
    }

    /** The server's list of problems, each a map with {@code type}, {@code loc}, {@code msg}; null when absent. */
    public List<Object> entries() {
        return entries;
    }
}
