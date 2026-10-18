---
title: Spring Boot configuration and profiles
technology: spring-boot
skill: spring-boot
topic: configuration-profiles
doc_type: REFERENCE
---

## Externalised configuration and precedence

Spring Boot reads properties from many sources with a defined precedence: command-line arguments override Java
system properties, which override operating-system environment variables, which override profile-specific
`application-{profile}.yml` files, which override `application.yml`. Environment variables map to properties by
relaxed binding: `SPRING_DATASOURCE_URL` sets `spring.datasource.url`. This lets the same artifact run in every
environment, with secrets supplied by the environment rather than committed files.

## Type-safe configuration properties

`@ConfigurationProperties(prefix = "app.mail")` binds a group of properties to a class or record, converting types
such as `Duration` ("30s") and `DataSize` ("10MB"); adding `@Validated` with Bean Validation annotations makes
invalid configuration fail at startup. It is preferred over scattering `@Value` annotations because it is typed,
validated, documented in one place and easy to test. `@Value` supports defaults (`${app.timeout:30s}`) and SpEL.

## Profiles

A profile names a set of beans and properties for an environment (`dev`, `test`, `prod`). Activate profiles with
`spring.profiles.active` (for example the `SPRING_PROFILES_ACTIVE` environment variable). `@Profile("prod")` on a
bean or configuration registers it only in that profile, and `application-prod.yml` overrides defaults. Keep profile
differences small: logic that differs per profile is logic that is not tested in production conditions.

## Secrets

Never commit secrets to configuration files. Read them from environment variables or a secret manager (Vault, a
cloud secret manager, Kubernetes secrets) and fail fast at startup when a required secret is missing. Avoid logging
configuration objects that hold secrets; override `toString` to redact them.
