package com.sanlam.fintech.withdrawal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the withdrawal service.
 *
 * <p>The original snippet hard-coded the AWS region and SNS topic ARN inside application code.
 * Binding them here keeps deployment-specific values out of the source and lets each environment
 * (local, test, staging, production) supply its own.</p>
 */
@ConfigurationProperties(prefix = "withdrawal")
public record WithdrawalProperties(
        String awsRegion,
        Events events,
        Outbox outbox) {

    public record Events(String topicArn) {
    }

    public record Outbox(long pollDelay, boolean publisherEnabled) {
    }
}
