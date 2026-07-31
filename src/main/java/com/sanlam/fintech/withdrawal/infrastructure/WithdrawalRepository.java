package com.sanlam.fintech.withdrawal.infrastructure;

import com.sanlam.fintech.withdrawal.domain.Withdrawal;
import com.sanlam.fintech.withdrawal.domain.WithdrawalStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class WithdrawalRepository {
    private final JdbcClient jdbc;

    public WithdrawalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Withdrawal> findByIdempotencyKey(String key) {
        return jdbc.sql("""
                SELECT id, account_id, amount, currency, status, idempotency_key, created_at
                  FROM withdrawals
                 WHERE idempotency_key = :key
                """)
                .param("key", key)
                .query((rs, rowNum) -> new Withdrawal(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getLong("account_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        WithdrawalStatus.valueOf(rs.getString("status")),
                        rs.getString("idempotency_key"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public void insert(Withdrawal withdrawal) {
        jdbc.sql("""
                INSERT INTO withdrawals
                    (id, account_id, amount, currency, status, idempotency_key, created_at)
                VALUES
                    (:id, :accountId, :amount, :currency, :status, :idempotencyKey, :createdAt)
                """)
                .param("id", withdrawal.id())
                .param("accountId", withdrawal.accountId())
                .param("amount", withdrawal.amount())
                .param("currency", withdrawal.currency())
                .param("status", withdrawal.status().name())
                .param("idempotencyKey", withdrawal.idempotencyKey())
                .param("createdAt", withdrawal.createdAt())
                .update();
    }
}
