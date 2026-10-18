---
title: REST API design
technology: rest
skill: rest-api
topic: http-api-design
doc_type: REFERENCE
---

## Resources and URIs

REST models an API as resources identified by URIs and manipulated through a uniform interface. URIs name resources
with nouns and hierarchy (`/customers/42/orders`), not actions (`/getOrders`); the HTTP method expresses the action.
Representations are usually JSON. REST is stateless: each request carries everything needed to process it, so the
server keeps no client session between requests.

## HTTP methods, safety and idempotency

GET reads and is safe (no side effects) and idempotent. PUT replaces a resource and is idempotent: repeating it leaves
the same state. DELETE is idempotent. PATCH applies a partial update and is not necessarily idempotent. POST creates a
resource or triggers a process and is neither safe nor idempotent, which is why retried POSTs need idempotency keys.
Idempotency is about the server state, not the response: a second DELETE may return 404 and still be idempotent.

## Status codes

2xx means success: 200 OK, 201 Created (with a Location header), 202 Accepted for asynchronous work, 204 No Content.
4xx means a client error: 400 Bad Request for malformed input, 401 Unauthorized when authentication is missing or
invalid, 403 Forbidden when the authenticated caller lacks permission, 404 Not Found, 409 Conflict for a state
conflict such as a duplicate or a version mismatch, 412 Precondition Failed for a failed If-Match, 422 for semantically
invalid input and 429 Too Many Requests. 5xx means a server error: 500, 502 Bad Gateway, 503 Service Unavailable and
504 Gateway Timeout.

## Caching and concurrency control

HTTP caching uses `Cache-Control` (max-age, no-store, private), and validators: an `ETag` or `Last-Modified` lets a
client revalidate with `If-None-Match` and receive 304 Not Modified. ETags also enable optimistic concurrency: a client
sends `If-Match` with the ETag it read, and the server rejects the update with 412 if the resource changed.

## Errors, filtering and HATEOAS

Return errors in a consistent machine-readable format such as RFC 9457 problem details. Filtering, sorting and
pagination use query parameters (`?status=open&sort=-createdAt&limit=20`). HATEOAS, the highest REST maturity level,
includes links to related actions in responses; most practical APIs stop at resources plus correct methods and status
codes.
