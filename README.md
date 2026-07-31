# Bank account withdrawal — improvement exercise

My take on the Sanlam withdrawal exercise. The business capability is unchanged from the original:
withdraw only when the balance is sufficient, then publish a "withdrawal succeeded" event. Everything
else is about making that one operation correct, observable and operable.

The brief asks for four things, and here's where each lives:

- **Approach** — this file (below).
- **Implementation choices and trade-offs** — `DECISIONS.md`.
- **The fixed code** — `src/main/java/...`, runnable (the original snippet is preserved in `docs/` and git history).
- **Unclear/assumed library usage** — the section near the end of this file.

`ARCHITECTURE.md` adds the data model, the request flow, and the metrics I'd add for production.

## What was wrong with the original

- The SNS publish is unreachable. Every branch returns before it, so no event is ever sent.
- `SELECT balance` then a separate `UPDATE` is a lost-update race: two concurrent withdrawals can
  both pass the check and overdraw the account.
- DB write plus SNS publish is a dual write. If one succeeds and the other fails, the balance and the
  event stream disagree — a reconciliation incident in a banking system.
- One class does HTTP, SQL, business logic, AWS setup and JSON, so none of it is testable in isolation.
- `SnsClient` is built in the constructor with a hard-coded region and topic ARN (and
  `Region.YOUR_REGION` doesn't compile).
- JSON is built with `String.format`, and the method returns plain strings with no status codes.
- No idempotency, no stored record, no logging.

## Approach

Same endpoint and behaviour, split into layers so each piece has one job:

```
api/             BankAccountController, response, error handling
application/     AccountService: the use case and transaction boundary
domain/          Withdrawal, WithdrawalEvent, status, exceptions
infrastructure/  repositories (atomic SQL) and the SNS outbox publisher
config/          SanlamBankProperties and beans
```

Three things do most of the work:

1. **Atomic debit.** One statement: `UPDATE ... SET balance = balance - :amount WHERE id = :id AND
   balance >= :amount`. Zero rows updated means insufficient funds. The database serialises concurrent
   requests on the row, so the read-then-write race is gone.

2. **Idempotency.** The API requires an `Idempotency-Key` header, stored on the withdrawal under a
   unique constraint and inserted before the debit. A repeat request replays the stored result instead
   of withdrawing again. The ordering matters; see `DECISIONS.md` section 2.

3. **Transactional outbox.** The balance change and an `outbox_events` row commit together, and a
   scheduled publisher relays those rows to SNS afterwards. A withdrawal is never lost because SNS
   happened to be down.

## The endpoint

Kept the original `POST /bank/withdraw` with `accountId` and `amount` as request params. The one
deliberate addition is a required `Idempotency-Key` header. The withdrawal is in the account's own
currency, so there's no currency parameter. Responses are JSON with proper status codes instead of a
plain string.

```
POST /bank/withdraw?accountId=42&amount=250.00
Idempotency-Key: 11111111-1111-1111-1111-111111111111
```

| Status | When |
|--------|------|
| 200 | withdrawal completed, or an idempotent replay |
| 400 | amount <= 0 / more than 2 decimals, or the Idempotency-Key header is missing |
| 404 | no account with that id |
| 409 | balance below the requested amount |

## Running it

The brief says the code needn't compile or run; I made it run anyway so the correctness claims are
demonstrable. It boots on in-memory H2 with seed accounts, no AWS needed.

```bash
mvn test            # concurrency + idempotency tests
mvn spring-boot:run
```

Seed accounts: 42 (1000 ZAR), 43 (50 ZAR), 44 (2500 USD).

```bash
curl -i -XPOST 'localhost:8080/bank/withdraw?accountId=42&amount=250.00' \
  -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111'
```

The SNS publisher is off by default (`withdrawal.outbox.publisher-enabled: false`) so it runs without
credentials; withdrawals still write outbox rows. H2 console at `/h2-console`
(`jdbc:h2:mem:withdrawals`).

## Unclear or assumed library usage

Things I relied on that are worth calling out, since the brief asks for them:

- **`JdbcClient` (Spring 6.1)** translates a unique-constraint violation into Spring's
  `DuplicateKeyException`. The concurrent-duplicate replay depends on that translation.
- **`ObjectMapper`** (Spring-managed) serialises `Instant` as ISO-8601 via the JSR-310 module, not as
  an epoch number. I rely on that default rather than configuring it by hand.
- **AWS SDK v2 `SnsClient`** resolves credentials lazily through the default provider chain at call
  time, so the bean is created even with no credentials present — only the publisher actually needs them.
- **Spring 6 native method validation**: constraints on `@RequestParam` throw
  `HandlerMethodValidationException` (→ 400) *without* `@Validated` on the class; adding `@Validated`
  switches to the older path that throws `ConstraintViolationException`, so I left it off deliberately.
- **SNS standard topics are at-least-once**, so consumers must deduplicate — the event carries a
  stable `eventId` for exactly that.

## Notes / out of scope

- Spring Boot 3.5, Java 21, Spring JDBC (`JdbcClient`), AWS SDK v2.
- `schema.sql` targets H2 for the demo. In Postgres the outbox "pending" index would be partial
  (`WHERE published_at IS NULL`), and real migrations would live in Flyway.
- **Security** is excluded by the brief (no auth, ownership checks, encryption, secrets).
- **Dead-letter escalation** is documented, not implemented: the schema reserves `dead_lettered_at`,
  but I preferred to show the seam honestly rather than half-build a retry engine. See `DECISIONS.md`.
