package com.atlas.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.rate-limit")
public class RateLimitProperties {

    /** Global default requests per minute per tenant. Overridden per tenant by quota. */
    private int requestsPerMinute = 60;

    /** Maximum burst size (token accumulation). */
    private int burstSize = 10;

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getBurstSize() {
        return burstSize;
    }

    public void setBurstSize(int burstSize) {
        this.burstSize = burstSize;
    }
}
