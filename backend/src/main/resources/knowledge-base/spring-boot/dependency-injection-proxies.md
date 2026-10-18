---
title: Spring dependency injection and proxies
technology: spring-boot
skill: spring-boot
topic: dependency-injection-proxies
doc_type: REFERENCE
---

## Inversion of control and injection styles

The Spring IoC container creates objects (beans), wires their dependencies and manages their lifecycle, so classes
receive collaborators instead of constructing them. Constructor injection is preferred: dependencies can be final,
the object is never half-initialised, missing dependencies fail at startup and the class is easy to test without
Spring. Field injection with `@Autowired` hides dependencies and needs reflection to test. With a single
constructor, `@Autowired` is optional. When several beans match a type, `@Primary` or `@Qualifier` chooses one.

## Bean scopes and lifecycle

The default scope is singleton: one instance per application context, shared by all threads, so singletons must be
stateless or thread-safe. Prototype creates a new instance per injection or lookup, and Spring does not call its
destroy callbacks. Web scopes are request and session. Injecting a prototype or request-scoped bean into a singleton
gives the singleton one instance forever unless an `ObjectProvider` or a scoped proxy is used. Lifecycle callbacks
are `@PostConstruct` after injection and `@PreDestroy` before shutdown. Circular constructor dependencies fail at
startup, which usually signals a design problem.

## Auto-configuration

Spring Boot auto-configuration classes are listed in `META-INF/spring/...AutoConfiguration.imports` and apply
conditionally: `@ConditionalOnClass` (a library is on the classpath), `@ConditionalOnMissingBean` (the application did
not define its own bean), `@ConditionalOnProperty`. Defining your own bean of the same type therefore backs the
default off. Running with `--debug` prints the condition evaluation report showing which configurations matched.

## AOP proxies and self-invocation

Features such as `@Transactional`, `@Cacheable`, `@Async` and method security work through proxies: the container
injects a proxy that runs the extra behaviour and then calls the target. Spring uses a JDK dynamic proxy when the
bean implements an interface (and proxying by interface is configured) and a CGLIB subclass otherwise; Spring Boot
defaults to CGLIB. Because the advice lives in the proxy, a call from one method of the bean to another method of
the same bean (`this.otherMethod()`) bypasses the proxy, so its annotation is ignored. CGLIB cannot proxy final
classes or final methods, and private methods are never advised. Fixes are to move the method to another bean or to
restructure the code.
