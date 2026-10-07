package com.bynn.decisionnode;

/** No response: the connection could not be made or was dropped. Retried. */
public class ConnectionError extends DecisionNodeError {
    private static final long serialVersionUID = 1L;

    ConnectionError(String message, Throwable cause) {
        super(message, cause);
    }
}
