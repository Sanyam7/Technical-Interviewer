package com.techinterviewer.evaluation.domain;

/**
 * Rating dimensions of all evaluators (AI_ARCHITECTURE.md sections 2.3, 2.6, 2.7 and 2.10). Which criteria an evaluator
 * uses belongs to the rubric registry, their weights to the scoring policy. Stored as JSON text inside
 * {@code criterion_scores}, so a constant can be added without a migration but must never be renamed or removed.
 */
public enum Criterion {
    // Technical questions (also used by the introduction, which is rated on COMMUNICATION only).
    CORRECTNESS,
    DEPTH,
    COMPLETENESS,
    COMMUNICATION,
    /** How the knowledge applies in real systems: when to use it, what goes wrong in production, how it is diagnosed. */
    PRACTICAL_UNDERSTANDING,
    /** Whether conclusions follow from stated reasons: cause and effect, trade-offs, why one option over another. */
    REASONING,
    // Coding. ALGORITHMIC_EFFICIENCY and CODE_QUALITY belong to the first coding rubric (coding.v1) and stay for its rows.
    ALGORITHMIC_EFFICIENCY,
    EDGE_CASES,
    CODE_QUALITY,
    /** Whether the approach is the right one for the problem: the idea, the data structures, the soundness of the method. */
    ALGORITHM,
    /** The solution's actual time complexity against what the constraints need. Capped by a timed-out performance test. */
    TIME_COMPLEXITY,
    /** The extra memory the solution uses against what the problem needs. */
    SPACE_COMPLEXITY,
    /** Whether a reader understands the code quickly: names, structure, idiomatic use of the language. */
    READABILITY,
    /** Whether the code is easy to change and test safely: small focused units, no duplication, clear boundaries. */
    MAINTAINABILITY,
    // System design (AI_ARCHITECTURE.md section 2.12). RELIABILITY_AND_TRADEOFFS and COMMUNICATION belong to the first design
    // rubric (system-design.v1) and stay for its rows.
    /** Functional and non-functional requirements and the scale, asked about or stated as assumptions. */
    REQUIREMENTS,
    /** The architecture: components, their responsibilities and how a request flows through them. */
    HIGH_LEVEL_DESIGN,
    /** The data model and the choice of database (SQL or NoSQL, keys, indexes) for the access patterns. */
    DATA_MODEL,
    /** Load growth: horizontal scaling, partitioning, hot spots, back-of-the-envelope capacity. */
    SCALABILITY,
    RELIABILITY_AND_TRADEOFFS,
    /** The interface: endpoints or messages, their parameters, pagination, idempotency keys, versioning. */
    API_DESIGN,
    /** Where a cache sits, what it holds, how it is invalidated or expires, and what happens on a miss or a stampede. */
    CACHING,
    /** Queues and asynchronous processing: what leaves the request path, delivery guarantees, back-pressure, consumers. */
    MESSAGING,
    /** Staying up: redundancy, replication, failover, no single point of failure. */
    AVAILABILITY,
    /** Correct results despite faults: durability, consistency, idempotency, no lost or duplicated work. */
    RELIABILITY,
    /** Behaviour when a dependency fails or slows down: timeouts, retries with backoff, circuit breakers, degradation, recovery. */
    FAILURE_HANDLING,
    /** Authentication, authorization, protection of data in transit and at rest, abuse prevention. */
    SECURITY,
    /** Metrics, logs, traces and alerts that show the system's health and locate a fault. */
    OBSERVABILITY,
    /** The choices made, what they cost and when another option would be better. */
    TRADE_OFFS
}
