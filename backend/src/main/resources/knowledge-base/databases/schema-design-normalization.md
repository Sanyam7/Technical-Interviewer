---
title: Schema design and normalization
technology: databases
skill: postgresql
topic: schema-design-normalization
doc_type: REFERENCE
---

## Keys and constraints

A primary key uniquely identifies a row; a natural key comes from the domain (an ISBN), a surrogate key is generated
(a sequence or a UUID). Surrogate keys are stable when business values change; a unique constraint still protects the
natural key. Foreign keys enforce referential integrity and define what happens on delete (`RESTRICT`, `CASCADE`, `SET
NULL`). Put invariants in the database with NOT NULL, CHECK and UNIQUE constraints, because application checks can be
bypassed or race. Random UUIDv4 keys fragment B-tree indexes on insert; time-ordered UUIDv7 keeps inserts local.

## Normal forms

Normalization removes redundancy so each fact is stored once and update anomalies cannot occur. First normal form
requires atomic values and no repeating groups. Second normal form removes partial dependencies on part of a composite
key. Third normal form removes transitive dependencies: non-key columns depend on the key, the whole key and nothing
but the key. Boyce-Codd normal form tightens 3NF so every determinant is a candidate key.

## When to denormalize

Denormalization deliberately duplicates data to make reads cheaper, for example storing an order total or a counter,
or a materialized view for reporting. It trades write complexity and the risk of inconsistency for read speed, so
it should be driven by measured read patterns and kept consistent by triggers, transactions or rebuild jobs.

## Data types and modelling choices

Use precise types: `numeric` for money (never floating point), `timestamptz` for instants, `text` with constraints
rather than arbitrary `varchar` lengths. JSONB columns suit truly variable attributes but give up constraints and
make queries harder, so core relational data belongs in columns. Many-to-many relations need a join table, whose
composite primary key also prevents duplicates.
