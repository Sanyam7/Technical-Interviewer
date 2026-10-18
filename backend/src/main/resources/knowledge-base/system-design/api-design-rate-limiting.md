---
title: API design and rate limiting
technology: system-design
skill: system-design
topic: api-design-rate-limiting
doc_type: DESIGN_REFERENCE
---

## Pagination

Offset pagination (`LIMIT 20 OFFSET 1000`) is simple but gets slower for deep pages because the database still reads
the skipped rows, and items shift when data changes between requests. Cursor (keyset) pagination returns an opaque
cursor encoding the last seen sort key and queries `WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id
DESC LIMIT 20`, which stays fast and stable; it cannot jump to an arbitrary page.

## Idempotency keys

Network failures make clients retry, so a POST that creates an order or a payment can run twice. With an idempotency
key, the client sends a unique key per logical operation and the server stores the key with the result; a retry with
the same key returns the stored result instead of repeating the side effect. The key must be stored atomically with
the operation's effect and expire after a retention window.

## Versioning and compatibility

APIs evolve; additive changes (new optional fields, new endpoints) are backward compatible, while removing or renaming
fields breaks clients. Version in the URL (`/v2/`) or a header, support old versions for a deprecation period, and
make clients tolerant of unknown fields.

## Rate-limiting algorithms

Rate limiting protects services from overload and abuse. A fixed window counter is simple but allows bursts at
window edges. A sliding window log is precise but stores every timestamp; a sliding window counter approximates it
cheaply. A token bucket refills tokens at a steady rate up to a capacity and lets bursts up to the capacity; a leaky
bucket smooths output to a constant rate. In a distributed system the counters live in a shared store such as Redis
with atomic operations (a Lua script), or each node enforces a share of the limit. Rejected requests get HTTP 429 with
a `Retry-After` header, and limits are applied per user, API key or IP.
