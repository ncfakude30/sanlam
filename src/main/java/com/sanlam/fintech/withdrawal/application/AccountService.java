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

// The withdrawal use case. Three things keep it correct: an atomic guarded debit, an idempotency
// key reserved before the debit, and an outbox row written in the same transaction as the balance
// change. See DECISIONS.md for the reasoning.
@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AccountService(
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
        // Fast path: replay a request we've already completed.
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
            // A concurrent duplicate won the race and committed first; replay its result.
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

        // Reserve the key first: a concurrent duplicate fails here, before any debit.
        withdrawalRepository.insert(withdrawal);

        // Guarded debit. 0 rows means insufficient funds, which rolls back the reservation too.
        int debited = accountRepository.debitIfSufficient(accountId, request.amount(), currency);
        if (debited == 0) {
            throw new InsufficientFundsException(accountId);
        }

        // Outbox write in the same transaction, so the event is durable once the balance commits.
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
