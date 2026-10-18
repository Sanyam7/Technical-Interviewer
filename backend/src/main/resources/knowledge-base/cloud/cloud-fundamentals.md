---
title: Cloud computing fundamentals
technology: cloud
doc_type: REFERENCE
---

## Service models

Infrastructure as a service (virtual machines, networks, disks) gives the most control and the most operational work.
Platform as a service and managed services (managed databases, queues, container platforms) let the provider handle
patching, backups and scaling. Serverless functions run code on demand and bill per invocation, scaling to zero, but
bring cold starts, execution time limits and statelessness. Managed services cost more per unit but usually less in
total once the engineering time to run the equivalent is counted.

## Regions, availability zones and high availability

A region is a geographic area containing several availability zones, physically separate datacenters with
independent power and networking. Running instances in at least two zones behind a load balancer survives the loss of
one datacenter; a database with a synchronous standby in another zone fails over automatically. Multi-region designs
protect against regional outages and reduce latency for distant users, but add data replication and consistency
problems and cost.

## Elasticity and autoscaling

Autoscaling adds or removes instances based on metrics such as CPU, request rate or queue length, so capacity follows
demand. It requires stateless instances, fast startup and health checks. Scale-out should react quickly and scale-in
slowly to avoid oscillation. Databases scale less elastically, so they often remain the limit.

## Identity and security

Cloud identity and access management grants permissions to users and workloads through roles and policies. Follow the
principle of least privilege, give workloads their own roles instead of long-lived access keys, and keep secrets in a
secret manager. Under the shared responsibility model the provider secures the infrastructure and the customer secures
what runs on it: configuration, data, identities and network exposure.

## Cost and the twelve-factor app

Cloud costs grow with idle resources, data egress and over-provisioning; tagging resources, right-sizing and
autoscaling keep them visible and bounded. The twelve-factor methodology describes cloud-native applications: config
in the environment, stateless processes, backing services attached through configuration, logs as event streams, fast
startup and graceful shutdown, and parity between development and production.
