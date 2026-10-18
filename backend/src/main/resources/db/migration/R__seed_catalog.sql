-- Repeatable seed of the catalog: nine skills and their topics (DATABASE_SCHEMA.md section 3.3).
-- Idempotent: upserts keyed on skills.slug and (skill, topic slug); never deletes rows.
-- Flyway re-applies this file whenever its checksum changes.
-- A topic's weight is its importance among the topics of its skill (AI_ARCHITECTURE.md section 2.15): the core topics an
-- interviewer asks first weigh more, so they get a skill's first questions; weights only order and share, they exclude nothing.

INSERT INTO skills (slug, name, category, description, active)
VALUES
    ('java', 'Java', 'LANGUAGE', 'The Java language and JVM: object-oriented design, collections, concurrency and memory management.', true),
    ('spring-boot', 'Spring Boot', 'FRAMEWORK', 'Building production services with Spring Boot: dependency injection, web, persistence, security and testing.', true),
    ('postgresql', 'PostgreSQL', 'DATABASE', 'Relational modelling and operating PostgreSQL: SQL, indexing, query planning, transactions and locking.', true),
    ('system-design', 'System Design', 'CONCEPT', 'Designing scalable and reliable distributed systems: trade-offs, components and failure handling.', true),
    ('react', 'React', 'FRAMEWORK', 'Building user interfaces with React: components, hooks, rendering behaviour, performance and state.', true),
    ('typescript', 'TypeScript', 'LANGUAGE', 'The TypeScript type system and tooling for building safe, maintainable JavaScript applications.', true),
    ('javascript', 'JavaScript', 'LANGUAGE', 'The JavaScript language: scope and closures, this, prototypes, equality and coercion, promises and the event loop.', true),
    ('rest-api', 'REST APIs', 'CONCEPT', 'Designing HTTP APIs: resources, methods and status codes, idempotency, caching and concurrency control, errors, pagination and versioning.', true),
    ('microservices', 'Microservices', 'CONCEPT', 'Building systems of services: boundaries and data ownership, communication, distributed transactions and sagas, resilience.', true)
ON CONFLICT (slug) DO UPDATE
SET name        = EXCLUDED.name,
    category    = EXCLUDED.category,
    description = EXCLUDED.description,
    active      = EXCLUDED.active;

INSERT INTO topics (skill_id, slug, name, description, min_difficulty, max_difficulty, weight)
SELECT s.id, t.topic_slug, t.name, t.description, t.min_difficulty, t.max_difficulty, t.weight
FROM (VALUES
    ('java', 'core-language-oop', 'Core language and OOP', 'Classes, interfaces, inheritance, polymorphism, immutability, equals/hashCode, records and enums.', 1::smallint, 3::smallint, 1.4::numeric(4, 2)),
    ('java', 'collections', 'Collections', 'List, Set, Map and Queue implementations, their complexity, iteration semantics and concurrent variants.', 1::smallint, 4::smallint, 1.5::numeric(4, 2)),
    ('java', 'generics', 'Generics', 'Type parameters, bounded types, wildcards, type erasure and PECS.', 2::smallint, 4::smallint, 0.9::numeric(4, 2)),
    ('java', 'exceptions', 'Exceptions', 'Checked versus unchecked exceptions, try-with-resources, exception design and error handling.', 1::smallint, 3::smallint, 1.2::numeric(4, 2)),
    ('java', 'streams-functional', 'Streams and functional programming', 'Lambdas, functional interfaces, the Stream API, collectors, Optional and laziness.', 2::smallint, 4::smallint, 1.2::numeric(4, 2)),
    ('java', 'concurrency', 'Concurrency', 'Threads, the Java memory model, synchronization, executors, CompletableFuture and virtual threads.', 2::smallint, 5::smallint, 1.3::numeric(4, 2)),
    ('java', 'jvm-memory-gc', 'JVM memory and garbage collection', 'Heap and stack layout, class loading, garbage collectors, tuning and diagnosing memory problems.', 3::smallint, 5::smallint, 1.0::numeric(4, 2)),
    ('spring-boot', 'dependency-injection-proxies', 'Dependency injection and proxies', 'The IoC container, bean scopes and lifecycle, auto-configuration and AOP proxy behaviour.', 1::smallint, 4::smallint, 1.5::numeric(4, 2)),
    ('spring-boot', 'configuration-profiles', 'Configuration and profiles', 'Externalised configuration, property binding, profiles and environment-specific setup.', 1::smallint, 3::smallint, 1.0::numeric(4, 2)),
    ('spring-boot', 'web-mvc-rest', 'Web MVC and REST', 'Controllers, request binding, validation, exception handling and REST API design.', 1::smallint, 4::smallint, 1.4::numeric(4, 2)),
    ('spring-boot', 'data-jpa', 'Spring Data JPA', 'Entities, repositories, fetching strategies, the N+1 problem and persistence-context behaviour.', 2::smallint, 5::smallint, 1.2::numeric(4, 2)),
    ('spring-boot', 'transactions', 'Transactions', 'Declarative transactions, propagation, isolation, rollback rules and proxy pitfalls.', 2::smallint, 5::smallint, 1.3::numeric(4, 2)),
    ('spring-boot', 'security', 'Spring Security', 'Filter chain, authentication and authorization, JWT and session handling, CORS and CSRF.', 2::smallint, 5::smallint, 1.1::numeric(4, 2)),
    ('spring-boot', 'testing', 'Testing', 'Unit, slice and integration tests, mocking, Testcontainers and test configuration.', 2::smallint, 4::smallint, 0.8::numeric(4, 2)),
    ('postgresql', 'sql-fundamentals', 'SQL fundamentals', 'Joins, aggregation, subqueries, window functions and set operations.', 1::smallint, 3::smallint, 1.5::numeric(4, 2)),
    ('postgresql', 'schema-design-normalization', 'Schema design and normalization', 'Keys, constraints, normal forms, data types and when to denormalize.', 1::smallint, 4::smallint, 1.2::numeric(4, 2)),
    ('postgresql', 'indexing', 'Indexing', 'B-tree, GIN, GiST and partial indexes, covering indexes and index selection.', 2::smallint, 5::smallint, 1.4::numeric(4, 2)),
    ('postgresql', 'query-planning', 'Query planning', 'EXPLAIN and EXPLAIN ANALYZE, statistics, join strategies and diagnosing slow queries.', 3::smallint, 5::smallint, 1.1::numeric(4, 2)),
    ('postgresql', 'transactions-isolation', 'Transactions and isolation', 'ACID, MVCC, isolation levels and anomalies such as phantom reads and write skew.', 2::smallint, 5::smallint, 1.3::numeric(4, 2)),
    ('postgresql', 'locking-concurrency', 'Locking and concurrency', 'Row and table locks, deadlocks, SELECT FOR UPDATE, advisory locks and optimistic versus pessimistic strategies.', 3::smallint, 5::smallint, 1.0::numeric(4, 2)),
    ('system-design', 'scalability-fundamentals', 'Scalability fundamentals', 'Vertical versus horizontal scaling, load balancing, statelessness and capacity estimation.', 1::smallint, 3::smallint, 1.5::numeric(4, 2)),
    ('system-design', 'caching', 'Caching', 'Cache placement, eviction and invalidation strategies, cache stampedes and consistency trade-offs.', 2::smallint, 4::smallint, 1.4::numeric(4, 2)),
    ('system-design', 'partitioning-replication', 'Partitioning and replication', 'Sharding strategies, replication modes, consistency models and the CAP theorem.', 3::smallint, 5::smallint, 1.1::numeric(4, 2)),
    ('system-design', 'messaging-async', 'Messaging and asynchronous processing', 'Queues and streams, delivery guarantees, idempotency, ordering and back-pressure.', 2::smallint, 5::smallint, 1.2::numeric(4, 2)),
    ('system-design', 'api-design-rate-limiting', 'API design and rate limiting', 'API contracts, versioning, pagination, idempotency keys and rate-limiting algorithms.', 2::smallint, 4::smallint, 1.2::numeric(4, 2)),
    ('system-design', 'reliability-observability', 'Reliability and observability', 'Timeouts, retries, circuit breakers, SLOs, metrics, logging and tracing.', 3::smallint, 5::smallint, 1.1::numeric(4, 2)),
    ('react', 'components-props-state', 'Components, props and state', 'Component composition, props, local state, lifting state up and controlled inputs.', 1::smallint, 3::smallint, 1.3::numeric(4, 2)),
    ('react', 'hooks', 'Hooks', 'useState, useEffect, useRef, useMemo, useCallback, custom hooks and the rules of hooks.', 1::smallint, 4::smallint, 1.5::numeric(4, 2)),
    ('react', 'effects-data-fetching', 'Effects and data fetching', 'Effect lifecycle and dependencies, cleanup, race conditions and server-state libraries.', 2::smallint, 4::smallint, 1.1::numeric(4, 2)),
    ('react', 'rendering-reconciliation', 'Rendering and reconciliation', 'The render and commit phases, keys, the virtual DOM diff, batching and concurrent rendering.', 2::smallint, 5::smallint, 1.4::numeric(4, 2)),
    ('react', 'performance', 'Performance', 'Memoization, code splitting, list virtualization, profiling and avoiding unnecessary renders.', 3::smallint, 5::smallint, 1.0::numeric(4, 2)),
    ('react', 'state-management', 'State management', 'Context, reducers and external stores, server versus client state and derived state.', 2::smallint, 4::smallint, 1.2::numeric(4, 2)),
    ('typescript', 'type-system-basics', 'Type system basics', 'Primitives, interfaces versus type aliases, unions, intersections, literal types and inference.', 1::smallint, 3::smallint, 1.5::numeric(4, 2)),
    ('typescript', 'narrowing-type-guards', 'Narrowing and type guards', 'typeof, in and instanceof narrowing, discriminated unions, user-defined guards and exhaustiveness checks.', 2::smallint, 4::smallint, 1.3::numeric(4, 2)),
    ('typescript', 'generics', 'Generics', 'Generic functions and types, constraints, defaults and inference.', 2::smallint, 5::smallint, 1.3::numeric(4, 2)),
    ('typescript', 'utility-mapped-conditional-types', 'Utility, mapped and conditional types', 'Partial, Pick, Omit and Record, mapped types, conditional types, infer and template literal types.', 3::smallint, 5::smallint, 1.0::numeric(4, 2)),
    ('typescript', 'strictness-tooling', 'Strictness and tooling', 'The strict compiler options, tsconfig, module resolution, declaration files and linting.', 1::smallint, 3::smallint, 0.8::numeric(4, 2)),
    ('javascript', 'language-core', 'Language core', 'Scope, hoisting and closures, this binding, prototypes and classes, equality and coercion, values and references.', 1::smallint, 4::smallint, 1.5::numeric(4, 2)),
    ('javascript', 'async-event-loop', 'Asynchrony and the event loop', 'The event loop, microtasks and macrotasks, promises, async and await, cancellation and timeouts.', 2::smallint, 5::smallint, 1.4::numeric(4, 2)),
    ('rest-api', 'http-api-design', 'HTTP API design', 'Resources and URIs, HTTP methods, safety and idempotency, status codes, caching and concurrency control, errors, pagination and versioning.', 1::smallint, 4::smallint, 1.0::numeric(4, 2)),
    ('microservices', 'architecture', 'Microservices architecture', 'Service boundaries and data ownership, synchronous and asynchronous communication, distributed transactions and sagas, resilience patterns.', 2::smallint, 5::smallint, 1.0::numeric(4, 2))
) AS t (skill_slug, topic_slug, name, description, min_difficulty, max_difficulty, weight)
JOIN skills s ON s.slug = t.skill_slug
ON CONFLICT (skill_id, slug) DO UPDATE
SET name           = EXCLUDED.name,
    description    = EXCLUDED.description,
    min_difficulty = EXCLUDED.min_difficulty,
    max_difficulty = EXCLUDED.max_difficulty,
    weight         = EXCLUDED.weight;
