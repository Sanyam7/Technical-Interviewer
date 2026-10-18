---
title: Microservices architecture
technology: microservices
skill: microservices
topic: architecture
doc_type: DESIGN_REFERENCE
---

## Monolith versus microservices

A microservice architecture splits a system into independently deployable services, each owning one business
capability and its own data. Benefits are independent deployment and scaling, team autonomy and fault isolation.
Costs are network latency and failures between services, distributed data consistency, harder testing and debugging,
and heavy operational needs (deployment automation, observability, service discovery). A well-structured modular
monolith is often the better start; split services out when team size or scaling needs justify the cost.

## Service boundaries and data ownership

Boundaries follow business capabilities or domain-driven design bounded contexts, not technical layers. Each service
owns its database and other services access that data only through its API or events; a shared database couples
services through the schema and defeats independent deployment. Services that must change together for every feature
indicate wrong boundaries (a distributed monolith).

## Synchronous and asynchronous communication

Synchronous calls (REST, gRPC) are simple but couple availability: if a downstream service is down or slow, the caller
suffers, and long call chains multiply latency and failure probability. Asynchronous events decouple services in
time; consumers build their own read models from events. An API gateway gives clients a single entry point and
handles authentication, routing and rate limiting. Service discovery (a registry or DNS in Kubernetes) finds instances.

## Distributed transactions and sagas

Two-phase commit across services is fragile and blocks on coordinator failure, so microservices use sagas: a sequence
of local transactions, each publishing an event that triggers the next, with compensating actions that undo earlier
steps when a later one fails (cancel the reservation, refund the payment). Choreography lets services react to each
other's events; orchestration uses a central coordinator that tells each service what to do, which is easier to follow
for long flows. Sagas give eventual consistency, so intermediate states must be acceptable or hidden.

## Resilience patterns

Calls between services need timeouts, retries with backoff for idempotent operations, circuit breakers and bulkheads.
Each service exposes health checks, emits metrics and propagates trace ids. Consumer-driven contract tests verify that
a provider still satisfies what its consumers rely on, replacing many slow end-to-end tests.
