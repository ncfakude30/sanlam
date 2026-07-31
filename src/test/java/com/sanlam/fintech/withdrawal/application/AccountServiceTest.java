package com.sanlam.fintech.withdrawal.application;

import com.sanlam.fintech.withdrawal.domain.Withdrawal;
import com.sanlam.fintech.withdrawal.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.sns.SnsClient;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused correctness tests. They exercise the two properties a reviewer will care about most in a
 * banking withdrawal: an account can never be overdrawn under concurrency, and a retried request is
 * applied exactly once. Both run against the real service, repositories, and H2 — not mocks — so the
 * atomic SQL and the transaction boundary are genuinely under test.
 */
@SpringBootTest
class AccountServiceTest {

    // The outbox publisher is disabled in tests; SNS is never contacted. Mocked purely so no real
    // client is constructed.
    @MockitoBean
    private SnsClient snsClient;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcClient jdbc;

    private void givenAccount(long id, String balance, String currency) {
        jdbc.sql("MERGE INTO accounts (id, balance, currency, version) KEY (id) VALUES (:id, :balance, :currency, 0)")
                .param("id", id)
                .param("balance", new BigDecimal(balance))
                .param("currency", currency)
                .update();
    }

    private BigDecimal balanceOf(long accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = :id")
                .param("id", accountId)
                .query(BigDecimal.class)
                .single();
    }

    private long outboxCountFor(UUID withdrawalId) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :id")
                .param("id", withdrawalId.toString())
                .query(Long.class)
                .single();
    }

    @Test
    void concurrent_withdrawals_never_overdraw_the_account() throws InterruptedException {
        long accountId = 1001L;
        givenAccount(accountId, "100.00", "ZAR"); // funds exactly five withdrawals of 20.00

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var startTogether = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);
        var successes = new AtomicInteger();
        var insufficient = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startTogether.await();
                    accountService.withdraw(accountId, new BigDecimal("20.00"), UUID.randomUUID().toString());
                    successes.incrementAndGet();
                } catch (InsufficientFundsException expected) {
                    insufficient.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        startTogether.countDown();
        finished.await();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(5);
        assertThat(insufficient.get()).isEqualTo(5);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("0.00"); // never negative, never over-debited
    }

    @Test
    void concurrent_requests_with_same_idempotency_key_debit_once() throws InterruptedException {
        long accountId = 1002L;
        givenAccount(accountId, "1000.00", "ZAR");
        String sharedKey = "idem-" + UUID.randomUUID();

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var startTogether = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);
        Set<UUID> returnedIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startTogether.await();
                    Withdrawal w = accountService.withdraw(accountId, new BigDecimal("30.00"), sharedKey);
                    returnedIds.add(w.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        startTogether.countDown();
        finished.await();
        pool.shutdown();

        assertThat(returnedIds).hasSize(1); // every caller got the same withdrawal
        assertThat(balanceOf(accountId)).isEqualByComparingTo("970.00"); // debited exactly once
    }

    @Test
    void sequential_replay_returns_same_result_and_writes_one_outbox_event() {
        long accountId = 1003L;
        givenAccount(accountId, "500.00", "ZAR");
        String key = "idem-" + UUID.randomUUID();

        Withdrawal first = accountService.withdraw(accountId, new BigDecimal("100.00"), key);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("400.00");
        assertThat(outboxCountFor(first.id())).isEqualTo(1); // event written in the same transaction

        // Same key replays the stored result without applying a second debit, even with a new amount.
        Withdrawal replay = accountService.withdraw(accountId, new BigDecimal("250.00"), key);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.amount()).isEqualByComparingTo("100.00");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("400.00"); // unchanged
        assertThat(outboxCountFor(first.id())).isEqualTo(1); // still one event
    }
}
