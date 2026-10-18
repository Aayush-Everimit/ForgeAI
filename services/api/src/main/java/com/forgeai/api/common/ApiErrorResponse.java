package com.forgeai.api.common;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        boolean retryable,
        String requestId,
        Map<String, String> fieldErrors,
        Instant timestamp
) {}
