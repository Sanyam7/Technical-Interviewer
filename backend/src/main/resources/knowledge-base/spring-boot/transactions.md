---
title: Spring transactions
technology: spring-boot
skill: spring-boot
topic: transactions
doc_type: REFERENCE
---

## Declarative transactions with @Transactional

`@Transactional` on a public method of a Spring bean starts a transaction before the method and commits after it,
through a proxy. Because it is proxy-based, a call from another method of the same class (self-invocation) does not
start a transaction, and annotations on private methods are ignored. Keep transactions short and do not perform slow
remote calls inside them, because they hold database connections and locks. `readOnly = true` is a hint that can
skip dirty checking and route to replicas.

## Rollback rules

By default Spring rolls back on unchecked exceptions (`RuntimeException` and `Error`) and commits on checked
exceptions. `rollbackFor = Exception.class` changes that. Catching an exception inside the transactional method and
not rethrowing it means the transaction commits. If an inner transactional call marks the shared transaction
rollback-only and the outer method catches the exception, the outer commit fails with
`UnexpectedRollbackException`.

## Propagation

Propagation decides what happens when a transactional method is called while a transaction exists. `REQUIRED` (the
default) joins the existing transaction or starts one. `REQUIRES_NEW` suspends the current transaction and runs in an
independent one that commits or rolls back on its own, useful for audit records that must survive a rollback, but it
needs a second connection. `NESTED` uses a savepoint inside the current transaction. `MANDATORY` fails without an
existing transaction, `SUPPORTS` runs with or without one, and `NOT_SUPPORTED` suspends it.

## Isolation and transactional events

`isolation` sets the database isolation level for the transaction (for example `REPEATABLE_READ` or `SERIALIZABLE`);
the database default applies otherwise. `@TransactionalEventListener(phase = AFTER_COMMIT)` runs a listener only
after a successful commit, which is the safe place to send emails or publish messages about data that is now
committed.
