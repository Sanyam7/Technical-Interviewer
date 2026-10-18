---
title: Spring Security
technology: spring-boot
skill: spring-boot
topic: security
doc_type: REFERENCE
---

## The security filter chain

Spring Security is a chain of servlet filters in front of the application. A `SecurityFilterChain` bean configures
which requests need authentication, how users authenticate and which headers are added. Authentication establishes
who the user is and stores an `Authentication` in the `SecurityContext` (per request or per session); authorization
decides what that user may do, with request rules (`authorizeHttpRequests`) and method security (`@PreAuthorize`).
Returning 401 means "not authenticated" and 403 means "authenticated but not allowed".

## Password storage

Passwords are stored as slow, salted, adaptive hashes such as BCrypt or Argon2 through a `PasswordEncoder`, never
encrypted or hashed with a fast hash like SHA-256. `DelegatingPasswordEncoder` prefixes the algorithm id so hashes can
be upgraded over time.

## JWT and stateless authentication

With bearer tokens (often JWTs) the server issues a signed token after login and the client sends it in the
`Authorization` header; the resource server validates the signature, expiry and audience without a session lookup.
JWTs cannot be revoked before expiry without extra state, so access tokens are short-lived and paired with refresh
tokens that are stored server-side, rotated on use and revocable. Storing tokens in `localStorage` exposes them to
XSS; httpOnly cookies avoid that but bring CSRF back into scope.

## CSRF and CORS

Cross-site request forgery tricks a browser into sending an authenticated request with the user's cookies. Spring
Security enables CSRF protection by default for cookie-based sessions; a stateless API authenticated only by a bearer
header is not vulnerable in the same way, which is why such APIs often disable it. CORS is a browser mechanism that
lets a server allow cross-origin requests from specific origins; it is not an authentication mechanism and a wildcard
origin must never be combined with credentials.
