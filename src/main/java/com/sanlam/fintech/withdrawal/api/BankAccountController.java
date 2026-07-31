package com.sanlam.fintech.withdrawal.api;

import com.sanlam.fintech.withdrawal.application.AccountService;
import com.sanlam.fintech.withdrawal.domain.Withdrawal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/bank")
public class BankAccountController {
    private final AccountService accountService;

    public BankAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // Keeps the original POST /bank/withdraw with accountId + amount request params. The one
    // deliberate addition is a required Idempotency-Key header so a retried withdrawal is applied
    // exactly once. Returns JSON with a proper status code instead of a plain string.
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResponse> withdraw(
            @RequestParam("accountId") long accountId,
            @RequestParam("amount")
            @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2)
            BigDecimal amount,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        Withdrawal result = accountService.withdraw(accountId, amount, idempotencyKey);
        return ResponseEntity.ok(WithdrawalResponse.from(result));
    }
}
