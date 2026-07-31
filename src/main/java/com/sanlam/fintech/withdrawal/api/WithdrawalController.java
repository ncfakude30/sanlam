package com.sanlam.fintech.withdrawal.api;

import com.sanlam.fintech.withdrawal.application.WithdrawalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bank/accounts/{accountId}/withdrawals")
public class WithdrawalController {
    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    public ResponseEntity<WithdrawalResponse> withdraw(
            @PathVariable long accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {

        var result = withdrawalService.withdraw(accountId, idempotencyKey, request);
        return ResponseEntity.ok(WithdrawalResponse.from(result));
    }
}
