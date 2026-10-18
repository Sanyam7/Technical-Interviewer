---
title: Choosing a database - relational and NoSQL
technology: databases
doc_type: REFERENCE
---

## Relational databases

Relational databases store data in tables with a fixed schema, enforce constraints and foreign keys, support joins
and ad-hoc queries in SQL, and give ACID transactions. They fit most business systems, where data is related and
correctness matters. They scale reads with replicas and caching; scaling writes beyond one primary requires
partitioning, which is harder.

## NoSQL data models

Key-value stores (Redis, DynamoDB) give very fast access by key and suit caches, sessions and counters. Document
stores (MongoDB) keep nested JSON-like documents and suit aggregates read and written as a whole; joins are weak, so
data is modelled around access patterns and often duplicated. Wide-column stores (Cassandra) handle huge write
volumes partitioned by key with tunable consistency, but queries must follow the partition key design. Graph
databases (Neo4j) traverse relationships efficiently. Search engines (Elasticsearch, OpenSearch) build inverted indexes
for full-text search and are usually a secondary store fed from the system of record.

## BASE and consistency trade-offs

Many distributed NoSQL stores choose BASE (basically available, soft state, eventually consistent): replicas converge
over time, and a read may return stale data. Some offer per-request consistency levels, such as quorum reads and
writes, where R + W > N guarantees that a read overlaps the latest write.

## How to choose

Start from the access patterns, the consistency requirements and the expected scale, not from fashion. A relational
database is a sound default; add a specialised store for a specific need (a cache, a search index, a time-series
store) and keep one system of record per piece of data. Polyglot persistence adds operational cost and the need to
keep stores in sync.

## Replication basics

Replication copies data to other nodes for availability and read scaling. With asynchronous replication the primary
does not wait for replicas, so a failover can lose recent writes and replicas lag; synchronous replication waits for
at least one replica, adding latency but no data loss. Reading from a lagging replica can show a user stale data right
after their own write, which "read your writes" routing avoids.
