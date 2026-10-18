---
title: Partitioning and replication
technology: system-design
skill: system-design
topic: partitioning-replication
doc_type: DESIGN_REFERENCE
---

## Sharding strategies

Partitioning (sharding) splits data across nodes so each holds a subset. Range partitioning keeps ordered key ranges
together, which makes range scans efficient but creates hot spots for sequential keys such as timestamps. Hash
partitioning spreads keys evenly but loses ordering. Consistent hashing places nodes and keys on a ring so adding or
removing a node moves only a small fraction of keys; virtual nodes even out the load. Choose a shard key with high
cardinality that matches the dominant access pattern, because queries across shards are expensive and transactions
across shards are hard.

## Replication modes

Single-leader replication sends all writes to one leader and copies them to followers, which serve reads.
Multi-leader replication accepts writes in several places (for example per region) and must resolve conflicting
writes. Leaderless replication (Dynamo style) writes to several replicas and reads from several, using quorums.
Replication can be synchronous (no data loss on failover, higher latency) or asynchronous (lower latency, possible loss
of recent writes, replica lag).

## The CAP theorem and PACELC

The CAP theorem says that when a network partition happens, a distributed system must choose between consistency
(every read sees the latest write) and availability (every request gets a response). Partitions are not optional, so
the real choice is CP or AP during a partition. PACELC adds that even without a partition there is a trade-off between
latency and consistency. Systems often choose differently per operation.

## Consistency models

Strong consistency (linearizability) makes the system behave like a single copy. Eventual consistency only promises
that replicas converge if writes stop. Useful intermediate guarantees are read-your-writes, monotonic reads and
causal consistency. With quorums, choosing R + W > N replicas makes reads overlap the latest successful write.

## Rebalancing and hot partitions

When load grows, partitions must move between nodes without downtime. Pre-splitting into many more partitions than
nodes makes rebalancing a matter of moving whole partitions. A celebrity user or a popular product can make one
partition hot; adding a random suffix to the key spreads its writes at the cost of reading all suffixes.
