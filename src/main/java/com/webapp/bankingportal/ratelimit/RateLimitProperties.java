package com.webapp.bankingportal.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /** Master switch. Disabled by default in tests via application-test.properties. */
    private boolean enabled = true;

    private final Bucket auth = new Bucket(10, 60);
    private final Bucket transaction = new Bucket(20, 60);
    private final Bucket defaultBucket = new Bucket(120, 60);

    @Data
    public static class Bucket {
        private int limit;
        private int windowSeconds;

        public Bucket() {
        }

        public Bucket(int limit, int windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }
}
