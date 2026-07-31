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

    // Injectable clock so tests can pin the time instead of calling Instant.now() everywhere.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // Region comes from config, not a compiled-in constant. Credentials resolve lazily at call
    // time, so this bean builds fine even when none are present.
    @Bean
    SnsClient snsClient(SanlamBankProperties properties) {
        return SnsClient.builder()
                .region(Region.of(properties.awsRegion()))
                .build();
    }

    // Explicit template so the service can catch a duplicate-key outside the transaction and
    // replay it. With @Transactional the transaction would already be rollback-only by then.
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
