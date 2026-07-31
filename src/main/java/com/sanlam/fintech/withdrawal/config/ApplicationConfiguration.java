package com.sanlam.fintech.withdrawal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

import java.time.Clock;

@Configuration
public class ApplicationConfiguration {

    /**
     * A single injectable clock keeps timestamps testable — tests can substitute a fixed clock
     * instead of reaching for {@code Instant.now()} scattered through the code.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Region comes from configuration rather than the compiled-in {@code Region.YOUR_REGION}
     * placeholder in the original snippet. Credentials are resolved by the SDK's default provider
     * chain at call time, so bean creation succeeds even without credentials present.
     */
    @Bean
    SnsClient snsClient(WithdrawalProperties properties) {
        return SnsClient.builder()
                .region(Region.of(properties.awsRegion()))
                .build();
    }

    /**
     * Explicit {@link TransactionTemplate} so the service can own its transaction boundary
     * programmatically. This lets us catch a duplicate-key violation <em>outside</em> the
     * transaction and replay it, which declarative {@code @Transactional} cannot do cleanly
     * (the exception would already have marked the transaction rollback-only).
     */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
