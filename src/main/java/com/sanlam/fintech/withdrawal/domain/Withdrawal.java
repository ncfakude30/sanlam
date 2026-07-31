package com.sanlam.fintech.withdrawal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Withdrawal(
        UUID id,
        long accountId,
        BigDecimal amount,
        String currency,
        WithdrawalStatus status,
        String idempotencyKey,
        Instant createdAt) {
}
