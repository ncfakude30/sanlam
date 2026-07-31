package com.sanlam.fintech.withdrawal.api;

import com.sanlam.fintech.withdrawal.application.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bank")
public class BankAccountController {
    private final AccountService accountService;

    public BankAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResponse> withdraw(
            @PathVariable long accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {

        var result = accountService.withdraw(accountId, idempotencyKey, request);
        return ResponseEntity.ok(WithdrawalResponse.from(result));
    }
}
