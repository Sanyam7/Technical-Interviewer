---
title: Reliability and observability
technology: system-design
skill: system-design
topic: reliability-observability
doc_type: DESIGN_REFERENCE
---

## Timeouts and retries

Every remote call needs a timeout; without one a slow dependency ties up threads until the caller fails too. Retries
handle transient errors but must use exponential backoff with jitter so clients do not retry in synchronised waves,
must be capped, and must only be used for idempotent operations or with idempotency keys. Retrying at every layer of
a deep call chain multiplies load (retry storms); retry at one layer and give the whole request a deadline that is
propagated downstream.

## Circuit breakers and bulkheads

A circuit breaker tracks failures of a dependency; when the failure rate crosses a threshold it opens and fails calls
immediately for a while, giving the dependency time to recover, then lets a few trial calls through (half-open)
before closing. Bulkheads isolate resources, such as separate thread pools or connection pools per dependency, so one
slow dependency cannot exhaust everything. Graceful degradation serves a reduced response (cached data, defaults)
instead of failing.

## SLIs, SLOs and error budgets

A service level indicator measures user-visible behaviour, such as the share of requests served successfully within
300 ms. A service level objective sets a target for it over a window, for example 99.9 percent per 30 days. The error
budget is the allowed unreliability (0.1 percent); while budget remains teams ship features, and when it is spent they
prioritise reliability. Alert on symptoms that burn the budget quickly, not on every cause.

## Metrics, logs and traces

Metrics are cheap numeric time series for dashboards and alerts; the RED method tracks rate, errors and duration per
service, and the USE method tracks utilisation, saturation and errors per resource. Structured logs record discrete
events with context such as a request id. Distributed tracing follows one request across services as a tree of spans
with a propagated trace id (W3C traceparent, OpenTelemetry), showing where latency comes from. High-cardinality labels
such as user ids belong in logs and traces, not in metric labels.

## Health checks and failure isolation

A liveness check tells the orchestrator to restart a stuck process; a readiness check tells the load balancer whether
to send traffic. A readiness check that depends on a shared downstream service can take every instance out of
rotation at once, so keep it about the instance itself.
