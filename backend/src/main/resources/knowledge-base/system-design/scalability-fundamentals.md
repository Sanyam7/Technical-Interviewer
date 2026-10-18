---
title: Scalability fundamentals
technology: system-design
skill: system-design
topic: scalability-fundamentals
doc_type: DESIGN_REFERENCE
---

## Vertical and horizontal scaling

Vertical scaling adds CPU, memory or faster disks to one machine; it is simple and keeps strong consistency, but has a
hard ceiling, gets expensive and leaves a single point of failure. Horizontal scaling adds more machines behind a load
balancer; it scales further and survives node failures, but requires stateless services, data partitioning and
handling of partial failures. Most systems scale the stateless tier horizontally first and the database vertically
for as long as possible.

## Stateless services

A stateless service keeps no client state in memory between requests: sessions, uploads and caches live in a shared
store (a database, Redis or object storage) or in the client's token. Any instance can then serve any request, so
instances can be added, removed or replaced freely, which enables autoscaling and rolling deployments. Sticky
sessions are a workaround that makes scaling and failover uneven.

## Load balancing

A load balancer spreads requests across healthy instances using algorithms such as round robin, least connections or
consistent hashing, and removes instances that fail health checks. Layer 4 balancers route TCP connections; layer 7
balancers understand HTTP and can route by path or header, terminate TLS and retry idempotent requests. The load
balancer itself must be redundant.

## Capacity estimation

Back-of-the-envelope estimation turns requirements into numbers: daily active users times actions per user gives
requests per day; divided by about 86,400 seconds gives average requests per second, and peak load is often two to
ten times the average. Storage is objects per day times object size times retention. Knowing rough latencies (memory
access in nanoseconds, a datacenter round trip around half a millisecond, a disk seek or cross-region round trip in
milliseconds) shows where caching and batching matter. State the assumptions explicitly.

## Latency, throughput and bottlenecks

Throughput is work per unit of time and latency is time per request; tail latency (p99) matters more than the average
because a page that fans out to many services waits for the slowest. Little's law (concurrency = throughput x
latency) sizes pools and queues. Find the bottleneck before scaling: adding application servers does not help when
the database is saturated.
