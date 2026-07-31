# Bank Account Withdrawal — Improvement Exercise

This the Sanlam Fintech withdrawal code-improvement exercise. The goal of the
original snippet is unchanged: **a client requests a withdrawal, the balance is reduced only when
funds are sufficient, and a successful-withdrawal event is published.** Everything here is in
service of making that single capability correct, observable, and operable under real conditions.

`DECISIONS.md` is a companion write-up structured for the to provide context on the tradeoffs taken — the *why* behind
each choice and the trade-offs I accepted.

---

## 1. What was wrong with the original

The original `BankAccountController` is a single method that mixes every concern and contains
several defects that matter in a banking context:

| # | Problem | Why it matters |
|---|---------|----------------|
| 1 | **The SNS publish is unreachable** — every branch `return`s before it. | The event is *never* published. The core "notify on withdrawal" requirement silently does nothing. |
| 2 | **`SELECT balance` then `UPDATE`** as two statements. | Two concurrent withdrawals both read the same balance and both pass the check → the account is overdrawn (lost update). |
| 3 | **DB update + SNS publish is a dual write.** | If the DB commits and SNS fails (or vice-versa) the balance and the event stream disagree. In finance that is a reconciliation incident. |
| 4 | **One class owns HTTP, SQL, business rules, AWS setup, and JSON.** | Nothing is unit-testable or reusable; every change touches everything. |
| 5 | **`SnsClient` built in the constructor; region + topic ARN hard-coded.** | Not configurable per environment, not mockable, `Region.YOUR_REGION` doesn't even compile. |
| 6 | **JSON built with `String.format`.** | Unescaped values produce malformed payloads; the contract is implicit. |
| 7 | **Returns plain `String`s** ("Withdrawal successful"). | No status codes, no machine-readable errors — callers can't reliably branch on the outcome. |
| 8 | **No idempotency, no durable record, no logs/metrics.** | Clients retry timed-out money operations; without idempotency that double-withdraws. Nothing is auditable or observable. |

Each of these maps directly to a quality the brief lists (correctness, fault tolerance, consistency,
maintainability, dependency management, observability, auditability).

---

## 2. Approach

The behaviour is preserved; the structure is split so each layer has one job:

```
api/            HTTP contract: validation, status codes, error mapping
application/    WithdrawalService — the use case + transaction boundary
domain/         Withdrawal, WithdrawalEvent, statuses, domain exceptions
infrastructure/ Repositories (atomic SQL) + SNS outbox publisher
config/         Externalised properties, beans (SnsClient, Clock, TransactionTemplate)
```

Three design choices carry the correctness of the whole thing:

### a. Atomic conditional debit
The balance check and decrement are a **single guarded statement**:

```sql
UPDATE accounts
   SET balance = balance - :amount, version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE id = :accountId AND currency = :currency AND balance >= :amount
```

If it updates 0 rows, funds were insufficient. Two concurrent requests can no longer both pass a
stale check — the database serialises them on the row. This replaces the read-then-write race
(problem #2) without any application-level locking.

### b. Reservation-first idempotency
The API requires an `Idempotency-Key` header. The withdrawal row carries that key under a **UNIQUE
constraint**, and it is **inserted before the debit**. So:

- The **fast path**: a repeat request finds the stored withdrawal and replays it.
- The **concurrent path**: two simultaneous requests with the same key contend on the *unique key*
  (not the account row). The loser fails with a duplicate-key violation *before it debits*; the
  service catches that outside the transaction and returns the winner's result.

The result is **exactly-once debiting** with a true replay — never a double debit, never a
misleading `409` for what is really a retry. The database constraint is the source of truth; the
up-front read is only an optimisation.

### c. Transactional outbox
Instead of publishing to SNS inside the request, the balance change **and** an `outbox_events` row
commit in the same transaction. A separate scheduled publisher (`SnsOutboxPublisher`) drains the
outbox to SNS afterwards. This removes the dual-write (problem #3): the event is durable the instant
the withdrawal commits, and a transient SNS outage can never roll back a completed withdrawal.

---

## 3. Running it

The exercise says the code needn't compile or run; I made it run anyway so the correctness claims
are demonstrable. It boots on an in-memory **H2** database with seed accounts, so no external
infrastructure is needed.

```bash
# from the repository root
mvn test          # runs the concurrency + idempotency tests
mvn spring-boot:run
```

Seed accounts (see `data.sql`): `42` (1000.00 ZAR), `43` (50.00 ZAR), `44` (2500.00 USD).

```bash
# success -> 200
curl -i -XPOST localhost:8080/bank/accounts/42/withdrawals \
  -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111' \
  -H 'Content-Type: application/json' \
  -d '{"amount": 250.00, "currency": "ZAR"}'

# same key -> identical result replayed (no second debit)
# over balance (account 43) -> 409 INSUFFICIENT_FUNDS
# unknown account -> 404 ACCOUNT_NOT_FOUND
# amount <= 0 or bad body -> 400 INVALID_REQUEST
```

The SNS publisher is **disabled by default** (`withdrawal.outbox.publisher-enabled: false`) so the
service runs without AWS credentials; withdrawals still record outbox rows. Set it to `true` (with
real AWS or LocalStack) to drain them. Inspect state at `http://localhost:8080/h2-console`
(JDBC URL `jdbc:h2:mem:withdrawals`).

### Request / response

```http
POST /bank/accounts/42/withdrawals
Idempotency-Key: 11111111-1111-1111-1111-111111111111
Content-Type: application/json

{ "amount": 250.00, "currency": "ZAR" }
```

```json
{
  "withdrawalId": "d9657330-ad7f-46a1-907f-71be1195bd76",
  "accountId": 42,
  "amount": 250.00,
  "currency": "ZAR",
  "status": "SUCCESSFUL"
}
```

---

## 4. Error model

| Status | Code | When |
|--------|------|------|
| `200 OK` | — | Withdrawal completed, or an idempotent replay |
| `400 Bad Request` | `INVALID_REQUEST` | Amount ≤ 0, > 2 decimals, or malformed body |
| `404 Not Found` | `ACCOUNT_NOT_FOUND` | No account with that id + currency |
| `409 Conflict` | `INSUFFICIENT_FUNDS` | Balance below requested amount |

---

## 5. Data model & portability

`schema.sql` runs on H2 (local/tests) and ports to PostgreSQL. One deliberate difference: the outbox
"pending" index is a plain index on `(published_at, created_at)` because H2 has no partial indexes;
in PostgreSQL I'd use a **partial** index `WHERE published_at IS NULL AND dead_lettered_at IS NULL`
to keep it small. This is called out inline in the file. In production the DDL would live in Flyway
or Liquibase rather than `schema.sql`.

---

## 6. Assumptions & unclear library usage

- **Stack:** Spring Boot 3.5, Java 21, Spring JDBC (`JdbcClient`), Jackson, AWS SDK v2.
- The Spring-managed `ObjectMapper` serialises `Instant` as ISO-8601 (JSR-310 module, no epoch
  timestamps) — I rely on that default rather than configuring it by hand.
- `JdbcClient` translates an H2/PostgreSQL unique-constraint violation into Spring's
  `DuplicateKeyException`; the idempotency replay depends on that translation.
- **SNS delivery is at-least-once** on standard topics, so consumers must deduplicate — the event
  carries a stable `eventId` and `withdrawalId` for exactly that.
- AWS credentials are resolved by the SDK's default provider chain at call time, so the `SnsClient`
  bean is created even when no credentials are present (only the publisher needs them).

---

## 7. Deliberately out of scope

- **Security** — excluded by the brief (no auth, ownership checks, encryption, secrets handling).
- **Dead-letter escalation** — the outbox records `attempts`/`last_error` and the schema reserves
  `dead_lettered_at`, but max-attempts → dead-letter + alerting is documented, not implemented
  (see `DECISIONS.md`). I preferred to show the seam honestly rather than ship a half-built retry
  engine within the ~2.5h scope.
- **Migrations tooling, multi-instance outbox leasing, ledger accounting** — discussed in
  `DECISIONS.md` as what I'd add next, kept out to avoid unjustified complexity.

Tests were optional; I included three focused ones (concurrency, concurrent-duplicate idempotency,
sequential replay) because they *prove* the two properties that matter most here.
