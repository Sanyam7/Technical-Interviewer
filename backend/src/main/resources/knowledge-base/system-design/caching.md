---
title: Caching in system design
technology: system-design
skill: system-design
topic: caching
doc_type: DESIGN_REFERENCE
---

## Where to cache

Caches sit at many layers: the browser and CDN for static and public content, a reverse proxy, an in-process cache in
each application instance (fastest but duplicated and inconsistent between instances), and a distributed cache such
as Redis shared by all instances. A cache helps when data is read much more often than it changes and when slightly
stale data is acceptable.

## Read and write strategies

Cache-aside (lazy loading) is the most common: the application reads the cache, and on a miss reads the database and
fills the cache. Read-through moves that logic into the cache layer. Write-through writes to the cache and the
database together, keeping the cache fresh at the cost of write latency. Write-behind (write-back) acknowledges after
writing the cache and persists later, which is fast but can lose data. On updates, deleting the cache entry is usually
safer than updating it, because concurrent updates can leave a stale value.

## Expiry, eviction and invalidation

A time to live bounds staleness. Eviction policies such as LRU (least recently used) and LFU (least frequently used)
decide what to drop when memory is full. Invalidation is hard because the database and the cache are updated
separately: a race between a reader filling the cache with an old value and a writer deleting the entry can leave
stale data until expiry. Event-driven invalidation (from change data capture) and short TTLs limit the damage.

## Cache stampede and hot keys

When a popular entry expires, many requests miss at once and hit the database together (a cache stampede or
thundering herd). Mitigations are request coalescing so only one caller recomputes the value, a lock per key, early
probabilistic refresh before expiry, and jitter on TTLs so entries do not expire together. A single very hot key can
overload one cache node; replicate it or keep a small local copy.

## Consistency trade-offs

A cache trades consistency for latency and load. Decide per data type how stale it may be: a product description can
be minutes old, an account balance should not be cached for decisions. Caching negative results (not found) protects
the database from repeated misses but must expire quickly.
