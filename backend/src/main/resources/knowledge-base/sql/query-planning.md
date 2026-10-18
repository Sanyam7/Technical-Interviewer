---
title: Query planning and EXPLAIN in PostgreSQL
technology: sql
skill: postgresql
topic: query-planning
doc_type: REFERENCE
---

## EXPLAIN and EXPLAIN ANALYZE

`EXPLAIN` shows the plan the optimizer chose, as a tree of nodes with estimated cost and row counts. `EXPLAIN
ANALYZE` actually runs the query and adds the real time and row counts per node, so it must not be run carelessly on
a data-modifying statement outside a transaction you roll back. `BUFFERS` shows pages read from cache or disk. The most
useful signal is a large gap between estimated and actual rows, which means the planner worked from bad estimates.

## Scan types

A sequential scan reads the whole table and is the right choice when a large share of rows is needed. An index scan
walks the index and fetches matching rows from the table, which is efficient for selective conditions. An index-only
scan answers from the index alone when every needed column is in it and the visibility map says the pages are
all-visible. A bitmap heap scan collects matching row locations from one or more indexes, sorts them by page and reads
each page once, a middle ground for medium selectivity.

## Join strategies

A nested loop join scans the inner side once per outer row and is good when the outer side is small and the inner side
has an index. A hash join builds a hash table of the smaller input and probes it with the other, good for large
unsorted equality joins when the table fits in `work_mem`. A merge join walks two inputs sorted on the join key.

## Statistics and common causes of slow queries

The planner estimates selectivity from statistics collected by `ANALYZE` (autovacuum runs it automatically):
histograms, most-common values and distinct counts per column. Stale statistics after bulk loads, correlated columns
(extended statistics with `CREATE STATISTICS` help) and functions applied to indexed columns (`WHERE lower(email) =
...` cannot use an index on `email`, but can use an expression index) are common causes of bad plans. A leading
wildcard `LIKE '%abc'` cannot use a B-tree index. Implicit type casts between a parameter and a column type can also
prevent index use.
