package com.sanlam.fintech.withdrawal.api;

import com.sanlam.fintech.withdrawal.domain.exception.AccountNotFoundException;
import com.sanlam.fintech.withdrawal.domain.exception.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ApiError> accountNotFound(AccountNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ApiError> insufficientFunds(InsufficientFundsException ex) {
        return error(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", ex.getMessage());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidRequest(HandlerMethodValidationException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The withdrawal request is invalid");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, Instant.now()));
    }

    record ApiError(String code, String message, Instant timestamp) {}
}
