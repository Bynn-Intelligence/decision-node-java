package com.bynn.decisionnode;

import java.util.List;
import java.util.Map;

/** What an error response carried, read once and handed to the error class. */
record ErrorInfo(
        Integer status,
        String errorType,
        String message,
        String requestId,
        String reason,
        Object body,
        Map<String, List<String>> headers) {}
