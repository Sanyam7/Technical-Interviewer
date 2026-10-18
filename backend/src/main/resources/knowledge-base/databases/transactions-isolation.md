---
title: Transactions and isolation levels
technology: databases
skill: postgresql
topic: transactions-isolation
doc_type: REFERENCE
---

## ACID

A transaction is atomic (all or nothing), consistent (it moves the database from one valid state to another,
respecting constraints), isolated (concurrent transactions do not see each other's intermediate states, to a degree
set by the isolation level) and durable (once committed it survives a crash, thanks to the write-ahead log flushed
at commit).

## MVCC

PostgreSQL implements isolation with multi-version concurrency control: an UPDATE writes a new row version and marks
the old one as expired, and each transaction reads the versions visible to its snapshot. Readers never block writers
and writers never block readers. Old versions become dead tuples that VACUUM reclaims; long-running transactions keep
old snapshots alive and cause table bloat.

## Isolation levels and anomalies

The SQL standard defines anomalies: a dirty read sees uncommitted data, a non-repeatable read sees a row change between
two reads, and a phantom read sees new rows matching a repeated query. Read Committed (the PostgreSQL default) takes a
new snapshot for every statement, so non-repeatable reads and phantoms can occur, but never dirty reads (PostgreSQL
never allows them). Repeatable Read uses one snapshot for the whole transaction; in PostgreSQL it also prevents
phantoms, and a concurrent update to a row it modifies makes it fail with a serialization error. Serializable adds
detection of dangerous dependency patterns (serializable snapshot isolation) and aborts transactions that could not
have run in some serial order; applications must retry them.

## Lost updates and write skew

A lost update happens when two transactions read a value, compute a new one and both write, so one change overwrites
the other; prevent it with an atomic UPDATE (`SET stock = stock - 1`), `SELECT ... FOR UPDATE`, or optimistic locking
with a version column. Write skew happens when two transactions read an overlapping set, each makes a decision and
writes different rows, together violating a rule (two doctors both going off call); only Serializable or explicit
locking prevents it.
