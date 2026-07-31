# Withdrawal Service

Sanlam Fintech technical assessment. The task was to improve a provided bank-account withdrawal
endpoint while preserving its business behaviour. The original operation is unchanged: a client
requests a withdrawal, the balance is debited only when funds are sufficient, and a
"withdrawal succeeded" event is published. Everything else is about making that one operation
correct, observable, and safe to run in production.

**Companion documents**

| Document | Purpose |
|----------|---------|
| [`DECISIONS.md`](DECISIONS.md) | Design decisions and trade-offs, written to be defended in the interview |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Data model, request/publish flows, and metrics to add |
| [`docs/`](docs/) | The original assessment brief, AI-usage guidelines, and the original code snippet |

The brief asks for four things: an outline of the approach, elaboration on the implementation choices,
the fixed code, and any unclear library usage. These map to the *Approach* section below,
`DECISIONS.md`, `src/main/java`, and the *Library notes* section respectively.

---

## Problems in the original

| # | Issue | Impact |
|---|-------|--------|
| 1 | The SNS publish sits after the method's `return` statements | The event is never published |
| 2 | `SELECT balance` then a separate `UPDATE` | Lost-update race; concurrent withdrawals overdraw the account |
| 3 | Database write and SNS publish are a dual write | Balance and event stream can disagree — a reconciliation incident |
| 4 | One class owns HTTP, SQL, business logic, AWS setup and JSON | Nothing is testable in isolation |
| 5 | `SnsClient` built in the constructor; region and topic ARN hard-coded | Not configurable or mockable; `Region.YOUR_REGION` does not compile |
| 6 | JSON assembled with `String.format`; method returns plain strings | Malformed payloads; no status codes for callers |
| 7 | No idempotency, durable record, or logging | Retries double-withdraw; nothing is auditable |

---

## Approach

The endpoint and behaviour are preserved. The single class is split into layers, each with one
responsibility:

| Layer | Responsibility |
|-------|----------------|
| `api` | HTTP contract: `BankAccountController`, response DTO, error-to-status mapping |
| `application` | `AccountService` — the use case and the transaction boundary |
| `domain` | `Withdrawal`, `WithdrawalEvent`, status, and domain exceptions |
| `infrastructure` | Repositories (atomic SQL via `JdbcClient`) and the SNS outbox publisher |
| `config` | `SanlamBankProperties` and beans (`SnsClient`, `Clock`, `TransactionTemplate`) |

Three decisions carry the correctness of the whole design:

1. **Atomic conditional debit** — the balance is checked and decremented in a single guarded
   statement (`UPDATE ... WHERE balance >= :amount`). Zero rows updated means insufficient funds, and
   the database serialises concurrent requests on the row. This removes the read-then-write race.

2. **Reservation-first idempotency** — the request carries an `Idempotency-Key`, stored on the
   withdrawal under a unique constraint and inserted *before* the debit. A retry replays the stored
   result instead of withdrawing again; a concurrent duplicate is rejected on the unique key before it
   can debit. See [`DECISIONS.md`](DECISIONS.md) §2.

3. **Transactional outbox** — the balance change and the event row commit in the same transaction; a
   scheduled publisher relays the event to SNS afterwards. A transient SNS outage can never roll back a
   committed withdrawal.

---

## API

```
POST /bank/withdraw?accountId={id}&amount={amount}
Idempotency-Key: {uuid}
```

| Response | Condition |
|----------|-----------|
| `200 OK` | Withdrawal completed, or an idempotent replay |
| `400 Bad Request` | Amount ≤ 0 or more than 2 decimals, or the `Idempotency-Key` header is missing |
| `404 Not Found` | No account with that id |
| `409 Conflict` | Balance below the requested amount |

**Example**

```bash
curl -i -XPOST 'http://localhost:8080/bank/withdraw?accountId=42&amount=250.00' \
  -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111'
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

The withdrawal is denominated in the account's own currency, so the request takes only `accountId` and
`amount` — matching the original signature, with the `Idempotency-Key` header as the one deliberate
addition.

---

## Getting started

**Prerequisites:** JDK 21 and Maven.

```bash
mvn test              # runs the concurrency and idempotency tests
mvn spring-boot:run   # starts the service on http://localhost:8080
```

The service boots on an in-memory H2 database seeded with three accounts, so no external
infrastructure or AWS credentials are required:

| Account | Balance | Currency |
|---------|---------|----------|
| 42 | 1000.00 | ZAR |
| 43 | 50.00 | ZAR |
| 44 | 2500.00 | USD |

The SNS publisher is disabled by default (`withdrawal.outbox.publisher-enabled: false`); withdrawals
still record outbox rows. Database state can be inspected at `/h2-console`
(JDBC URL `jdbc:h2:mem:withdrawals`).

---

## Library notes

Behaviour I relied on that is worth calling out:

- **`JdbcClient` (Spring 6.1)** translates a unique-constraint violation into Spring's
  `DuplicateKeyException`; the concurrent-duplicate replay depends on this.
- **`ObjectMapper`** serialises `Instant` as ISO-8601 via the JSR-310 module rather than an epoch number.
- **AWS SDK v2 `SnsClient`** resolves credentials lazily through the default provider chain, so the
  bean is created even without credentials — only the publisher needs them.
- **Spring 6 method validation** on `@RequestParam` throws `HandlerMethodValidationException` (→ 400)
  without `@Validated` on the class; `@Validated` would switch to the legacy path, so it is omitted.
- **SNS standard topics deliver at-least-once**, so consumers deduplicate on the event's `eventId`.

---

## Out of scope

- **Security** — excluded by the brief (no authentication, ownership checks, encryption, or secrets).
- **Dead-letter escalation** — the schema reserves `dead_lettered_at` and the publisher records
  `attempts`/`last_error`, but max-attempts escalation is documented rather than half-built
  (see [`DECISIONS.md`](DECISIONS.md)).
- **Migrations tooling, multi-instance outbox leasing, ledger accounting** — discussed in
  `DECISIONS.md` as the next steps.

**Stack:** Java 21, Spring Boot 3.5, Spring JDBC (`JdbcClient`), AWS SDK v2, H2 (local/test).
