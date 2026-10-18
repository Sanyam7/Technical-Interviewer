---
title: Messaging and asynchronous processing
technology: system-design
skill: system-design
topic: messaging-async
doc_type: DESIGN_REFERENCE
---

## Queues and logs

Asynchronous messaging decouples producers from consumers in time and load: the producer returns after publishing and
consumers process at their own pace, absorbing spikes. A message queue (RabbitMQ, SQS) delivers each message to one
consumer and deletes it after acknowledgement, which suits work distribution. A log-based stream (Kafka) appends
messages to partitioned, replicated logs that consumers read at their own offsets; messages are retained and can be
replayed, and many consumer groups can read the same stream independently.

## Delivery guarantees and idempotency

At-most-once delivery may lose messages; at-least-once delivery retries until acknowledged and may deliver
duplicates; exactly-once processing end to end is achieved by at-least-once delivery plus idempotent consumers. An
idempotent consumer records processed message ids (or uses natural idempotency such as upserts) so a redelivered
message has no second effect. Acknowledge only after processing, otherwise a crash loses the message.

## Ordering and partitioning

Kafka guarantees order only within a partition, so messages that must stay ordered (all events of one order) need the
same partition key. Parallelism is bounded by the number of partitions in a consumer group. Retries and dead-letter
handling can reorder messages; designs that tolerate reordering, for example by versioning events, are more robust.

## The dual-write problem and the outbox pattern

Writing to the database and publishing a message in two separate steps can leave them inconsistent if the process
fails between them. The transactional outbox pattern writes the message into an outbox table in the same database
transaction as the business change, and a relay (polling or change data capture) publishes it afterwards, giving
at-least-once publication.

## Back-pressure and poison messages

When consumers fall behind, queues grow; bounded queues, consumer autoscaling on lag and rate limiting producers keep
the system stable. A poison message that always fails should be retried a limited number of times with backoff and
then moved to a dead-letter queue for inspection, instead of blocking the partition forever.
