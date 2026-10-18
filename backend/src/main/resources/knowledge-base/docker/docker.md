---
title: Docker and containers
technology: docker
doc_type: REFERENCE
---

## Containers versus virtual machines

A container is an isolated process on the host's kernel: Linux namespaces give it its own view of processes, network
and file system, and cgroups limit its CPU and memory. A virtual machine runs a full guest operating system on a
hypervisor. Containers therefore start in milliseconds and are lighter, but share the host kernel, so their isolation
is weaker than a VM's.

## Images and layers

An image is a read-only stack of layers, each produced by an instruction in the Dockerfile, plus metadata such as
the entrypoint. Layers are cached and shared between images, and a container adds a thin writable layer on top. Order
instructions from least to most frequently changing: copy the dependency manifest and install dependencies before
copying the source code, so a code change does not invalidate the dependency layer. Tag images with immutable
versions or digests rather than relying on `latest`.

## Writing good Dockerfiles

Multi-stage builds compile in a build image with the full toolchain and copy only the artifact into a small runtime
image, reducing size and attack surface. Run as a non-root user, use a `.dockerignore` to keep secrets and build output
out of the context, never bake secrets into image layers (they remain in history even if deleted later) and prefer
minimal or distroless base images that are patched regularly. Use the exec form of `ENTRYPOINT` so the application is
PID 1 and receives SIGTERM for graceful shutdown.

## Networking and storage

Containers on the same user-defined network reach each other by service name through Docker's DNS. Publishing a port
(`-p 8080:8080`) maps a host port to the container. The container's writable layer disappears with the container, so
persistent data goes in volumes (managed by Docker) or bind mounts (a host directory). Databases in containers need
volumes, or every restart loses data.

## Docker Compose and orchestration

Docker Compose defines a multi-container application (an API, a database, a cache) in one YAML file for local
development and testing, with networks, volumes, environment variables and health checks. `depends_on` with a health
condition waits for a dependency to be healthy, not just started. In production, orchestrators such as Kubernetes
schedule containers across machines, restart failed ones, scale them and roll out new versions.
