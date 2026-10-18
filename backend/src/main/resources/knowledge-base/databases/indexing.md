---
title: Database indexing
technology: databases
skill: postgresql
topic: indexing
doc_type: REFERENCE
---

## B-tree indexes

The default B-tree index keeps keys sorted in a balanced tree, supporting equality, range conditions, ORDER BY and
prefix LIKE patterns in O(log n). A composite index on (a, b) serves conditions on a, or on a and b, but not on b
alone, because the leftmost-prefix rule follows the sort order; put equality columns before range columns. Indexes
speed up reads but slow down every insert, update and delete and use disk and memory, so each index should serve a real
query.

## Specialised index types

GIN (generalised inverted index) indexes the elements inside a value: full-text search vectors, JSONB keys and arrays.
GiST supports geometric data, ranges and nearest-neighbour searches. BRIN stores summaries per block range and is tiny
and effective for huge, naturally ordered tables such as append-only time series. Hash indexes support only equality.
Vector extensions such as pgvector add approximate nearest-neighbour indexes (HNSW, IVFFlat) for embeddings.

## Partial, expression and covering indexes

A partial index covers only rows matching a predicate (`WHERE status = 'PENDING'`), keeping it small when queries
always filter on that condition; it also enables conditional uniqueness. An expression index indexes a computed value
such as `lower(email)` and is used only when the query uses the same expression. A covering index includes extra
columns (`INCLUDE (name)`) so a query can be answered by an index-only scan.

## Why an index is not used

The planner skips an index when the condition is not selective (reading most of the table sequentially is cheaper),
when statistics are stale, when the column is wrapped in a function or cast, when the query uses a leading wildcard,
or when the column order of a composite index does not match the filter. Low-cardinality columns such as booleans
rarely benefit from a standalone index.
