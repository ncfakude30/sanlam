package com.sanlam.fintech.withdrawal.domain.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(long accountId) {
        super("Account %d has insufficient funds".formatted(accountId));
    }
}
