package com.sanlam.fintech.withdrawal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalEvent(
        UUID eventId,
        UUID withdrawalId,
        long accountId,
        BigDecimal amount,
        String currency,
        String status,
        Instant occurredAt,
        int schemaVersion) {

    public static WithdrawalEvent from(Withdrawal withdrawal, Instant occurredAt) {
        return new WithdrawalEvent(
                UUID.randomUUID(), withdrawal.id(), withdrawal.accountId(),
                withdrawal.amount(), withdrawal.currency(), withdrawal.status().name(),
                occurredAt, 1);
    }
}
