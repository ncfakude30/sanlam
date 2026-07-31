package com.sanlam.fintech.withdrawal.domain.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(long accountId) {
        super("Account %d was not found".formatted(accountId));
    }
}
