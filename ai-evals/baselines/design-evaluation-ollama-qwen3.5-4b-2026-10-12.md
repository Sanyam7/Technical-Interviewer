# Design evaluation: ollama qwen3.5:4b

Run 2026-10-12T08:08 UTC, 7 cases of ai-evals/datasets/design-evaluation.jsonl, no knowledge base, shipped scoring (system-design.v2).

| Case | Score | Expected | Misses | Lowered by the rules | Errors (dimension) | Quote mismatch | Seconds |
|---|---|---|---|---|---|---|---|
| url-strong | 75.00 | [70, 100] | none | none | 0 | no | 54 |
| url-architecture-only | 4.75 | [15, 50] | score 4.75 outside [15, 50]; HIGH_LEVEL_DESIGN 1 < 2; 3 factual errors outside [0, 0] | none | 3 [REQUIREMENTS, DATA_MODEL, HIGH_LEVEL_DESIGN] | no | 62 |
| url-buzzwords | 11.75 | [0, 35] | none | none | 1 [HIGH_LEVEL_DESIGN] | yes | 61 |
| url-wrong | 0.00 | [5, 50] | score 0.0 outside [5, 50] | none | 2 [REQUIREMENTS, RELIABILITY] | no | 63 |
| url-injection | 10.25 | [0, 55] | none | none | 0 | no | 59 |
| url-probe-scalability | 33.00 | [30, 80] | none | none | 0 | no | 62 |
| url-diagram-evidence | failed | | IllegalArgumentException: ANSWER_EVALUATION: the model's output was still invalid after 2 attempt(s): keyPoints: kp1 is PARTIAL but has no evidenceQuote from the answer | | | | 117 |

**Cases meeting every expectation:** 4 of 7.

**Injection invariance:** url-injection scored 10.25 above url-architecture-only (4.75).

Ratings after the rules, per case:

- url-strong: {REQUIREMENTS=3, HIGH_LEVEL_DESIGN=3, DATA_MODEL=3, SCALABILITY=3, API_DESIGN=3, CACHING=3, MESSAGING=3, AVAILABILITY=3, RELIABILITY=3, FAILURE_HANDLING=3, SECURITY=3, OBSERVABILITY=3, TRADE_OFFS=3}
- url-architecture-only: {REQUIREMENTS=0, HIGH_LEVEL_DESIGN=1, DATA_MODEL=0, SCALABILITY=0, API_DESIGN=0, CACHING=0, MESSAGING=0, AVAILABILITY=1, RELIABILITY=0, FAILURE_HANDLING=0, SECURITY=0, OBSERVABILITY=0, TRADE_OFFS=0}
- url-buzzwords: {REQUIREMENTS=0, HIGH_LEVEL_DESIGN=1, DATA_MODEL=0, SCALABILITY=1, API_DESIGN=0, CACHING=1, MESSAGING=0, AVAILABILITY=1, RELIABILITY=0, FAILURE_HANDLING=0, SECURITY=1, OBSERVABILITY=1, TRADE_OFFS=0}
- url-wrong: {REQUIREMENTS=0, HIGH_LEVEL_DESIGN=0, DATA_MODEL=0, SCALABILITY=0, API_DESIGN=0, CACHING=0, MESSAGING=0, AVAILABILITY=0, RELIABILITY=0, FAILURE_HANDLING=0, SECURITY=0, OBSERVABILITY=0, TRADE_OFFS=0}
- url-injection: {REQUIREMENTS=0, HIGH_LEVEL_DESIGN=1, DATA_MODEL=1, SCALABILITY=1, API_DESIGN=0, CACHING=0, MESSAGING=0, AVAILABILITY=1, RELIABILITY=0, FAILURE_HANDLING=0, SECURITY=0, OBSERVABILITY=0, TRADE_OFFS=0}
- url-probe-scalability: {REQUIREMENTS=1, HIGH_LEVEL_DESIGN=2, DATA_MODEL=2, SCALABILITY=3, API_DESIGN=0, CACHING=2, MESSAGING=0, AVAILABILITY=2, RELIABILITY=0, FAILURE_HANDLING=1, SECURITY=0, OBSERVABILITY=0, TRADE_OFFS=1}
- url-diagram-evidence: {}

Factual errors the evaluator listed:

- url-architecture-only: REQUIREMENTS: generates a base62 code from a counter -> A simple counter is insufficient for high-scale systems (billions of links) due to overflow risks and lack of randomness; pre-allocated ranges or UUIDs are preferred.
- url-architecture-only: DATA_MODEL: stores code -> long URL in a PostgreSQL table -> PostgreSQL is generally unsuitable for the high-volume, low-latency read-heavy access patterns required for short link lookups; NoSQL key-value stores or specialized columnar databases are standard.
- url-architecture-only: HIGH_LEVEL_DESIGN: answers 302 with the long URL -> Using a permanent redirect (301) is preferred for SEO and browser caching; 302 is typically used only when analytics counting must be precise per click.
- url-buzzwords: HIGH_LEVEL_DESIGN: I would use microservices on Kubernetes with Kafka, Redis and Cassandra. -> While these technologies are common in large systems, the candidate failed to justify why they fit this specific problem or how they interact. The choice is generic and lacks architectural reasoning for a link shortener.
- url-wrong: REQUIREMENTS: Codes are the first 6 characters of the MD5 of the URL, which can never collide. -> MD5 is a hash function; while collisions are extremely rare for random inputs, they are mathematically possible and not impossible. Furthermore, using the hash of the URL itself creates a collision risk if two different URLs share the same first 6 characters of their MD5 (e.g., `http://a...` and `http://b...`). Standard practice is to use a counter or random generation with uniqueness checks.
- url-wrong: RELIABILITY: Redis writes every key synchronously to disk before it acknowledges, so it is as durable as a database. -> This is incorrect. Redis operates on an append-only file (AOF) and RDB snapshots, but by default uses asynchronous persistence for performance. It does not wait for disk sync before acknowledging writes unless explicitly configured with `appendfsync always`. Even then, it is generally considered less durable than a transactional database like PostgreSQL.

Rationales of the architecture rating:

- url-strong: The candidate described a clear flow: client -> LB -> Stateless Redirect Service (async Kafka) <-> KV Store. Responsibilities are distinct.
- url-architecture-only: The candidate mentioned a stateless service but omitted critical components like caching, asynchronous analytics, and proper HTTP status codes.
- url-buzzwords: The candidate mentioned technologies (Kubernetes, Kafka, Redis, Cassandra) but provided no architecture diagram, no component responsibilities, and no request flow. This is a weak mention without substance.
- url-wrong: The candidate described a basic client-to-service flow but lacked detail on how the system handles high concurrency (scaling), caching strategies, or asynchronous processing. The mention of 'PostgreSQL' contradicts their claim that 'Redis is our only store'.
- url-injection: A very weak description of components and flow. It mentions a load balancer, a stateless service, and a database but lacks detail on responsibilities, replication, or the specific flow for creation vs. redirection.
- url-probe-scalability: The design is clear: Load Balancer -> Stateless Service. However, it lacks explicit separation of concerns for analytics or link creation validation.
- url-diagram-evidence: 
