package com.sanlam.fintech.withdrawal.infrastructure;

import com.sanlam.fintech.withdrawal.domain.OutboxEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxEvent event) {
        jdbc.sql("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload, created_at, attempts)
                VALUES
                    (:id, :aggregateType, :aggregateId, :eventType, :payload, :createdAt, 0)
                """)
                .param("id", event.id())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .param("createdAt", event.createdAt())
                .update();
    }

    public List<PendingOutboxEvent> findPending(int limit) {
        return jdbc.sql("""
                SELECT id, event_type, payload, attempts
                  FROM outbox_events
                 WHERE published_at IS NULL
                   AND dead_lettered_at IS NULL
                 ORDER BY created_at
                 LIMIT :limit
                """)
                .param("limit", limit)
                .query(PendingOutboxEvent.class)
                .list();
    }

    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("""
                UPDATE outbox_events
                   SET published_at = :publishedAt,
                       last_error = NULL
                 WHERE id = :id
                   AND published_at IS NULL
                """)
                .param("id", id)
                .param("publishedAt", publishedAt)
                .update();
    }

    public void recordFailure(UUID id, String error) {
        jdbc.sql("""
                UPDATE outbox_events
                   SET attempts = attempts + 1,
                       last_error = :error
                 WHERE id = :id
                """)
                .param("id", id)
                .param("error", error)
                .update();
    }

    public record PendingOutboxEvent(UUID id, String eventType, String payload, int attempts) {}
}
