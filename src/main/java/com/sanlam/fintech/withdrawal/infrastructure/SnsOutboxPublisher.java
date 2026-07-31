package com.sanlam.fintech.withdrawal.infrastructure;

import com.sanlam.fintech.withdrawal.config.SanlamBankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Clock;
import java.util.Map;

// Drains the outbox to SNS. Off by default (withdrawal.outbox.publisher-enabled) so the app runs
// without AWS credentials. Publishing happens after commit, so a failed publish just leaves the
// event pending for the next cycle; it never rolls back a completed withdrawal.
@Component
@ConditionalOnProperty(name = "withdrawal.outbox.publisher-enabled", havingValue = "true")
public class SnsOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(SnsOutboxPublisher.class);

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final SnsClient snsClient;
    private final String topicArn;
    private final Clock clock;

    public SnsOutboxPublisher(
            OutboxRepository outboxRepository,
            SnsClient snsClient,
            SanlamBankProperties properties,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.snsClient = snsClient;
        this.topicArn = properties.events().topicArn();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${withdrawal.outbox.poll-delay}")
    public void publishPending() {
        for (var event : outboxRepository.findPending(BATCH_SIZE)) {
            try {
                snsClient.publish(PublishRequest.builder()
                        .topicArn(topicArn)
                        .message(event.payload())
                        .messageAttributes(Map.of(
                                "eventType", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(event.eventType())
                                        .build()))
                        .build());

                outboxRepository.markPublished(event.id(), clock.instant());
                log.info("Outbox event published eventId={} eventType={}", event.id(), event.eventType());
            } catch (RuntimeException ex) {
                // Record it and move on; the event stays pending and retries next cycle. Bounded
                // retries + dead-lettering would go here (see DECISIONS.md), left out of scope.
                outboxRepository.recordFailure(event.id(), safeMessage(ex));
                log.error("Outbox event publication failed eventId={} attempts={}",
                        event.id(), event.attempts() + 1, ex);
            }
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
