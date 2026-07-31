# Bank account withdrawal — improvement exercise

My take on the Sanlam withdrawal exercise. The behaviour is the same as the original: withdraw only
when the balance is sufficient, then publish a "withdrawal succeeded" event. Everything else is about
making that safe and operable. `DECISIONS.md` has the reasoning behind the choices.

The original snippet is kept at the repo root (`BankAccountController.java`, `WithdrawalEvent.java`)
for reference.

## What was wrong with the original

- The SNS publish is unreachable. Every branch returns before it, so no event is ever sent.
- `SELECT balance` then a separate `UPDATE` is a lost-update race: two concurrent withdrawals can
  both pass the check and overdraw the account.
- DB write plus SNS publish is a dual write. If one succeeds and the other fails, the balance and the
  event stream disagree.
- One class does HTTP, SQL, business logic, AWS setup and JSON, so none of it is testable in isolation.
- `SnsClient` is built in the constructor with a hard-coded region and topic ARN (and
  `Region.YOUR_REGION` doesn't compile).
- JSON is built with `String.format`, and the method returns plain strings with no status codes.
- No idempotency, no stored record, no logging.

## Approach

Split into the usual layers, one job each:

```
api/             controller, request/response, error handling
application/     WithdrawalService: the use case and transaction boundary
domain/          Withdrawal, WithdrawalEvent, status, exceptions
infrastructure/  repositories (atomic SQL) and the SNS outbox publisher
config/          properties and beans
```

Three things do most of the work:

1. Atomic debit. One statement: `UPDATE ... SET balance = balance - :amount WHERE id = :id AND
   currency = :ccy AND balance >= :amount`. Zero rows updated means insufficient funds. The database
   serialises concurrent requests on the row, so the read-then-write race is gone.

2. Idempotency. The API takes an `Idempotency-Key` header, stored on the withdrawal under a unique
   constraint and inserted before the debit. A repeat request replays the stored result instead of
   withdrawing again. The ordering matters; see `DECISIONS.md` section 2.

3. Transactional outbox. The balance change and an `outbox_events` row commit together, and a
   scheduled publisher relays those rows to SNS afterwards. A withdrawal is never lost because SNS
   happened to be down.

## Running it

Boots on in-memory H2 with seed accounts, no AWS needed.

```bash
mvn test            # concurrency + idempotency tests
mvn spring-boot:run
```

Seed accounts: 42 (1000 ZAR), 43 (50 ZAR), 44 (2500 USD).

```bash
curl -i -XPOST localhost:8080/bank/accounts/42/withdrawals \
  -H 'Idempotency-Key: k1' -H 'Content-Type: application/json' \
  -d '{"amount": 250.00, "currency": "ZAR"}'
```

Responses: 200 on success (or replay), 409 insufficient funds, 404 unknown account, 400 bad amount.

The SNS publisher is off by default (`withdrawal.outbox.publisher-enabled: false`) so it runs without
credentials; withdrawals still write outbox rows. H2 console at `/h2-console`
(`jdbc:h2:mem:withdrawals`).

## Notes

- Spring Boot 3.5, Java 21, Spring JDBC (`JdbcClient`), AWS SDK v2.
- `schema.sql` targets H2 for the demo. In Postgres the outbox "pending" index would be partial
  (`WHERE published_at IS NULL`), and real migrations would live in Flyway.
- SNS standard topics are at-least-once, so the event carries an `eventId` for consumers to dedupe.
- Out of scope: security (per the brief), and dead-letter escalation. The schema reserves
  `dead_lettered_at` but I left that documented rather than half-built. See `DECISIONS.md`.
