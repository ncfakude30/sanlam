package com.sanlam.fintech.withdrawal.api;

import com.sanlam.fintech.withdrawal.domain.Withdrawal;
import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawalResponse(
        UUID withdrawalId,
        long accountId,
        BigDecimal amount,
        String currency,
        String status) {

    public static WithdrawalResponse from(Withdrawal withdrawal) {
        return new WithdrawalResponse(
                withdrawal.id(),
                withdrawal.accountId(),
                withdrawal.amount(),
                withdrawal.currency(),
                withdrawal.status().name());
    }
}
