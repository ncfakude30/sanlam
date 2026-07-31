package com.sanlam.fintech.withdrawal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Region and topic ARN come from config instead of being hard-coded like the original snippet.
@ConfigurationProperties(prefix = "withdrawal")
public record SanlamBankProperties(
        String awsRegion,
        Events events,
        Outbox outbox) {

    public record Events(String topicArn) {
    }

    public record Outbox(long pollDelay, boolean publisherEnabled) {
    }
}
