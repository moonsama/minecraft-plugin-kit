package com.moonsama.portal;

import com.moonsama.portal.PortalModels.RateLimit;

public final class PortalException extends RuntimeException {
    private final int status;
    private final String code;
    private final String responseBody;
    private final Long retryAfterSeconds;
    private final RateLimit rateLimit;

    public PortalException(
            int status,
            String code,
            String message,
            String responseBody,
            Long retryAfterSeconds,
            RateLimit rateLimit
    ) {
        super(message);
        this.status = status;
        this.code = code;
        this.responseBody = responseBody;
        this.retryAfterSeconds = retryAfterSeconds;
        this.rateLimit = rateLimit;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String responseBody() {
        return responseBody;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public RateLimit rateLimit() {
        return rateLimit;
    }

    public boolean retryable() {
        return status >= 500 || status == 429;
    }
}
