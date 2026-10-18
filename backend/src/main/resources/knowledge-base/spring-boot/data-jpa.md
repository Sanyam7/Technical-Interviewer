---
title: Spring Data JPA and Hibernate
technology: spring-boot
skill: spring-boot
topic: data-jpa
doc_type: REFERENCE
---

## Entities, repositories and the persistence context

A JPA entity maps a class to a table; Hibernate is the default JPA provider in Spring Boot. Spring Data generates
repository implementations from interfaces, including derived queries from method names (`findByEmail`) and
`@Query` for JPQL or native SQL. Inside a transaction, the persistence context (the first-level cache) guarantees one
managed instance per row and tracks changes: modifying a managed entity is enough for Hibernate to issue an UPDATE at
flush time (dirty checking), without calling `save`. Entities become detached when the persistence context closes.

## The N+1 query problem

The N+1 problem happens when code loads N parent entities with one query and then touches a lazy association on each,
triggering one extra query per parent. It is invisible in tests with little data and slow in production. Fixes are a
fetch join (`join fetch` in JPQL), an `@EntityGraph`, batch fetching (`hibernate.default_batch_fetch_size`) or a DTO
projection that selects exactly the needed columns. Enable SQL logging or statement counting in tests to catch it.

## Fetch types and LazyInitializationException

`@ManyToOne` and `@OneToOne` are eager by default and `@OneToMany` and `@ManyToMany` are lazy. Eager fetching
everywhere loads too much data; a common recommendation is to make every association lazy and fetch explicitly per
use case. Accessing a lazy association after the persistence context is closed throws
`LazyInitializationException`. The open-session-in-view setting hides this by keeping the session open during view
rendering, at the cost of database access from the web layer; many teams disable it.

## Identity, equality and optimistic locking

Entity `equals` and `hashCode` should be based on a stable business key or the identifier with care for transient
entities, never on all mutable fields. `@Version` adds optimistic locking: an UPDATE includes the version in its
WHERE clause, and if another transaction changed the row, Hibernate throws `OptimisticLockException` instead of
silently overwriting it (a lost update).
