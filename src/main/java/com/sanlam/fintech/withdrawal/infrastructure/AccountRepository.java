package com.sanlam.fintech.withdrawal.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsWithCurrency(long accountId, String currency) {
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM accounts
                 WHERE id = :accountId
                   AND currency = :currency
                """)
                .param("accountId", accountId)
                .param("currency", currency)
                .query(Integer.class)
                .single() > 0;
    }

    public int debitIfSufficient(long accountId, BigDecimal amount, String currency) {
        return jdbc.sql("""
                UPDATE accounts
                   SET balance = balance - :amount,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :accountId
                   AND currency = :currency
                   AND balance >= :amount
                """)
                .param("amount", amount)
                .param("accountId", accountId)
                .param("currency", currency)
                .update();
    }
}
