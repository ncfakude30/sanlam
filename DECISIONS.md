# Notes on the decisions

Reasoning behind the main choices so I can talk through them, roughly in order of how much they
matter to correctness.

## 1. Atomic debit instead of read-then-write

The original read the balance, then updated it in a second statement. Two concurrent withdrawals both
read the same balance, both pass the check, both subtract, and the account goes negative. Classic
lost update.

I do it in one statement: `UPDATE ... WHERE balance >= :amount`. The row lock serialises the two
requests, and zero rows updated means insufficient funds. A `SELECT ... FOR UPDATE` would also work
but holds the lock longer for no real gain here.

## 2. Idempotency, and why the ordering matters

Clients retry money requests that time out, so the API takes an `Idempotency-Key` and the withdrawal
stores it under a unique constraint.

The ordering is the part worth discussing. If you keep a pre-check and debit first, two simultaneous
duplicates both see "no existing withdrawal" and go to debit. The loser blocks on the account row,
and by the time it runs the balance may already be down, so it comes back with a 409. That's wrong: a
retry should replay success.

So I insert the withdrawal before the debit. Now the loser blocks on the unique key, fails with a
duplicate-key error before it debits anything, and I catch that outside the transaction and return
the winner's row. Exactly-once debit, a real replay, no false 409. The pre-check is just a fast path;
the constraint is the actual guard.

## 3. TransactionTemplate over @Transactional

To replay a duplicate I have to catch the duplicate-key error and then run another query. With
`@Transactional` the transaction is already rollback-only by the time I catch it, and self-invocation
wouldn't go through the proxy anyway. `TransactionTemplate` puts the boundary in plain sight: the
`execute()` block is the transaction, the catch sits outside it.

## 4. Transactional outbox instead of publishing to SNS directly

Publishing inside the request is a dual write: either the DB commits and SNS fails (event lost), or
SNS sends and the transaction rolls back (event for a withdrawal that didn't happen). The outbox
writes the event in the same transaction as the balance, and a separate publisher relays it later.
The trade-off is at-least-once delivery, so consumers dedupe on `eventId`; I'd rather send twice than
lose a financial event. Publishing synchronously after commit doesn't fix it, a crash in that window
still loses the event.

## 5. Why not Kafka / CQRS / microservices

Nothing here justifies them, and the brief warns about unjustified complexity. One service, one
transaction and an outbox cover the actual problem. The outbox is a clean place to extract a broker
later if event volume needs it.

## 6. Left out on purpose

- Dead-letter escalation. The outbox records `attempts` and `last_error` and the schema reserves
  `dead_lettered_at`, but max-attempts to dead-letter plus alerting isn't built. It's a small state
  machine that needs its own tests, and it's the first thing I'd add.
- Multi-instance publishing would need `FOR UPDATE SKIP LOCKED` so instances claim different rows.
- A real core-banking system would use an append-only ledger rather than a mutable balance. Out of
  scope, but that's the honest production answer.
- Security, excluded by the brief.

## 7. Smaller things

- `BigDecimal` for money, validated positive with at most 2 decimals. Never `double`.
- Currency is part of the debit predicate, so you can't withdraw ZAR from a USD account.
- Constructor injection, no field `@Autowired`.
- One status, `SUCCESSFUL`. I didn't invent PENDING/FAILED states the original never had.

## Tests

Three that cover what matters: 10 threads on an account funded for 5 (exactly 5 succeed, balance ends
at 0), 10 threads with the same idempotency key (same withdrawalId, debited once), and a sequential
replay (same result, one outbox row). Next I'd add a rollback test and Postgres integration tests.
