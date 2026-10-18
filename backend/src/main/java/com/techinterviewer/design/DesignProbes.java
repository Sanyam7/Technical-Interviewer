package com.techinterviewer.design;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * What each design dimension means and the generic scenario an interviewer probes it with when the problem has no scenario of
 * its own. The generic scenarios are the classic interview questions ("What happens if traffic increases 100x?"); the probe
 * writer applies them to the candidate's own design, and when it cannot, the scenario is asked as it is.
 */
public final class DesignProbes {

    /** The dimensions of the system design rubric. */
    public static final Set<Criterion> DIMENSIONS = RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN);

    private static final Map<Criterion, String> MEANING = new EnumMap<>(Criterion.class);
    private static final Map<Criterion, String> GENERIC = new EnumMap<>(Criterion.class);

    static {
        dimension(Criterion.REQUIREMENTS, "requirements: the functional and non-functional requirements and the scale the design is built for",
                "Before we go deeper: which requirements and what scale are you designing for, and what numbers are you assuming?");
        dimension(Criterion.HIGH_LEVEL_DESIGN, "architecture: the components, their responsibilities and how a request flows through them",
                "What happens, component by component, when a user performs the main action of this product?");
        dimension(Criterion.API_DESIGN, "API design: the endpoints or messages, their parameters, pagination and idempotency",
                "What does the API look like: the main endpoints or messages, with their key parameters and responses?");
        dimension(Criterion.DATA_MODEL, "data model and database: what is stored where, SQL or NoSQL, keys, indexes and partitioning",
                "Would you use SQL or NoSQL for the core data, and why? What are the main tables or collections and their keys?");
        dimension(Criterion.CACHING, "caching: where a cache sits, what it holds, how it is invalidated, and what happens on a miss",
                "Where would you introduce caching, and how do you keep the cache consistent with the source of truth?");
        dimension(Criterion.MESSAGING, "queues and asynchronous processing: what leaves the request path, delivery guarantees, back-pressure",
                "Which work would you take off the request path, and what would you put between the producer and the consumer?");
        dimension(Criterion.SCALABILITY, "scalability: how the design grows with load, partitioning, hot spots, capacity estimates",
                "What happens if traffic increases 100x? What breaks first, and what do you change?");
        dimension(Criterion.AVAILABILITY, "availability: redundancy, replication, failover, no single point of failure",
                "A whole availability zone goes down. What keeps the system serving users, and what do they notice?");
        dimension(Criterion.RELIABILITY, "reliability: durability and correctness under faults, idempotency, no lost or duplicated work",
                "A request fails halfway through, after some steps succeeded. How do you make sure nothing is lost or applied twice?");
        dimension(Criterion.FAILURE_HANDLING, "failure handling: timeouts, retries with backoff, circuit breakers, graceful degradation, recovery",
                "How would you handle a database failure? What does the system do while the database is down, and after it recovers?");
        dimension(Criterion.SECURITY, "security: authentication, authorization, protection of data, abuse prevention",
                "How do you secure this system: who may do what, how is sensitive data protected, and how do you stop abuse?");
        dimension(Criterion.OBSERVABILITY, "observability: metrics, logs, traces and alerts that show health and locate faults",
                "How would you know this system is unhealthy before users tell you? What would you measure and alert on?");
        dimension(Criterion.TRADE_OFFS, "trade-offs: the choices made, what they cost, and when another option would be better",
                "What is the most important trade-off in your design? What did you give up, and when would you choose differently?");
    }

    private DesignProbes() {
    }

    private static void dimension(Criterion criterion, String meaning, String probe) {
        MEANING.put(criterion, meaning);
        GENERIC.put(criterion, probe);
    }

    /** The classic question for {@code criterion}. */
    public static String generic(Criterion criterion) {
        String probe = GENERIC.get(criterion);
        if (probe == null) {
            throw new IllegalArgumentException(criterion + " is not a dimension of the system design rubric");
        }
        return probe;
    }

    /** What {@code criterion} covers, in words for a prompt. */
    public static String meaning(Criterion criterion) {
        String meaning = MEANING.get(criterion);
        if (meaning == null) {
            throw new IllegalArgumentException(criterion + " is not a dimension of the system design rubric");
        }
        return meaning;
    }
}
