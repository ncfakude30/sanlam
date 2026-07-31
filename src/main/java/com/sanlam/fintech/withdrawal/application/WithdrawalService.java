package com.sanlam.fintech.withdrawal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanlam.fintech.withdrawal.api.WithdrawalRequest;
import com.sanlam.fintech.withdrawal.domain.*;
import com.sanlam.fintech.withdrawal.infrastructure.AccountRepository;
import com.sanlam.fintech.withdrawal.infrastructure.OutboxRepository;
import com.sanlam.fintech.withdrawal.infrastructure.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the withdrawal use case and its transaction boundary.
 *
 * <p>Correctness rests on three things:</p>
 * <ol>
 *   <li><b>Atomic conditional debit</b> — the balance is decremented in a single
 *       {@code UPDATE ... WHERE balance >= amount}, so two concurrent withdrawals can never both
 *       pass a stale balance check and overdraw the account.</li>
 *   <li><b>Reservation-first idempotency</b> — the withdrawal row (with a UNIQUE idempotency key)
 *       is inserted <em>before</em> the debit. Two concurrent requests with the same key therefore
 *       contend on the unique key rather than the account row: the loser fails with a duplicate-key
 *       violation before it debits, and we replay the winner's result. The database constraint is
 *       the source of truth; the up-front read is only a fast path for the common sequential retry.</li>
 *   <li><b>Transactional outbox</b> — the balance change and the event record commit together, so
 *       we never have a committed withdrawal without a recorded event (or vice versa).</li>
 * </ol>
 */
@Service
public class WithdrawalService {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final AccountRepository accountRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public WithdrawalService(
            AccountRepository accountRepository,
            WithdrawalRepository withdrawalRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public Withdrawal withdraw(long accountId, String idempotencyKey, WithdrawalRequest request) {
        // Fast path: a previously completed request with this key is replayed without re-running it.
        var replay = withdrawalRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            log.info("Idempotent withdrawal replay accountId={} withdrawalId={} idempotencyKey={}",
                    accountId, replay.get().id(), idempotencyKey);
            return replay.get();
        }

        try {
            return transactionTemplate.execute(status ->
                    executeWithdrawal(accountId, idempotencyKey, request));
        } catch (DuplicateKeyException concurrentDuplicate) {
            // A concurrent request with the same key won the race and committed first. Its withdrawal
            // is now visible, so we replay it — the caller gets the same result, and the account was
            // debited exactly once.
            log.info("Concurrent duplicate withdrawal replayed accountId={} idempotencyKey={}",
                    accountId, idempotencyKey);
            return withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> concurrentDuplicate);
        }
    }

    private Withdrawal executeWithdrawal(long accountId, String idempotencyKey, WithdrawalRequest request) {
        String currency = request.currency().toUpperCase(Locale.ROOT);
        if (!accountRepository.existsWithCurrency(accountId, currency)) {
            throw new AccountNotFoundException(accountId);
        }

        Instant occurredAt = clock.instant();
        Withdrawal withdrawal = new Withdrawal(
                UUID.randomUUID(), accountId, request.amount(), currency,
                WithdrawalStatus.SUCCESSFUL, idempotencyKey, occurredAt);

        // Reserve the idempotency key first. Under a concurrent duplicate this throws
        // DuplicateKeyException here, before any debit happens.
        withdrawalRepository.insert(withdrawal);

        // Atomic, guarded debit: 0 rows means the balance was insufficient (the account's existence
        // was already confirmed above), which rolls the whole transaction back — including the
        // reservation just inserted.
        int debited = accountRepository.debitIfSufficient(accountId, request.amount(), currency);
        if (debited == 0) {
            throw new InsufficientFundsException(accountId);
        }

        // Same-transaction outbox write: the event is durable the moment the balance change commits.
        WithdrawalEvent event = WithdrawalEvent.from(withdrawal, occurredAt);
        outboxRepository.insert(new OutboxEvent(
                event.eventId(),
                "Withdrawal",
                withdrawal.id().toString(),
                "WithdrawalSucceeded",
                serialize(event),
                occurredAt));

        log.info("Withdrawal completed accountId={} withdrawalId={} amount={} currency={}",
                accountId, withdrawal.id(), withdrawal.amount(), withdrawal.currency());
        return withdrawal;
    }

    private String serialize(WithdrawalEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize withdrawal event", ex);
        }
    }
}
