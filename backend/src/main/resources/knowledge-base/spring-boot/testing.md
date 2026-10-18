---
title: Testing Spring Boot applications
technology: spring-boot
skill: spring-boot
topic: testing
doc_type: REFERENCE
---

## The test pyramid

Most tests should be fast unit tests of plain classes without Spring, with fewer slice tests and fewer end-to-end
tests. Constructor injection makes unit testing easy: create the object with test doubles. Mock only what you own or
what is expensive, and prefer fakes or real in-memory objects for simple collaborators; over-mocking tests the
implementation instead of the behaviour.

## Slice tests and full context tests

Slice annotations load only part of the application: `@WebMvcTest` loads the web layer with `MockMvc` and mocks
services, `@DataJpaTest` loads JPA repositories with a transaction rolled back after each test, and `@JsonTest`
covers serialisation. `@SpringBootTest` loads the whole context and is the slowest. Spring caches application
contexts between test classes with the same configuration, so using many distinct configurations (or `@MockitoBean`
combinations, or `@DirtiesContext`) multiplies startup time.

## Testcontainers

Testcontainers starts real dependencies such as PostgreSQL, Kafka or Redis in Docker for integration tests, so tests
run against the same database engine and version as production instead of an in-memory substitute like H2 whose SQL
dialect and behaviour differ. `@ServiceConnection` wires the container's connection details into Spring Boot
automatically. A reusable container per test run keeps the suite fast.

## Test data and flakiness

Each test should create the data it needs and not depend on the order of other tests. Tests that use the current
time or random values should inject a `Clock` and a seeded random source so results are reproducible. Waiting with
fixed sleeps causes flaky tests; wait for a condition with a timeout (for example Awaitility) instead.
