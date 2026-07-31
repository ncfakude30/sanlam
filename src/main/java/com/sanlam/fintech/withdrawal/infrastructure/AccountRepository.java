package com.sanlam.fintech.withdrawal.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Returns the account's own currency, or empty if the account doesn't exist. The withdrawal
    // is denominated in whatever currency the account holds, so callers don't pass one in.
    public Optional<String> findCurrency(long accountId) {
        return jdbc.sql("SELECT currency FROM accounts WHERE id = :accountId")
                .param("accountId", accountId)
                .query(String.class)
                .optional();
    }

    // Atomic guarded debit: decrements only if the balance covers it. 0 rows means insufficient
    // funds, and the database serialises concurrent debits on the row.
    public int debitIfSufficient(long accountId, BigDecimal amount) {
        return jdbc.sql("""
                UPDATE accounts
                   SET balance = balance - :amount,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :accountId
                   AND balance >= :amount
                """)
                .param("amount", amount)
                .param("accountId", accountId)
                .update();
    }
}
