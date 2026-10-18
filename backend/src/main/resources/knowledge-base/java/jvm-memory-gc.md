---
title: JVM memory and garbage collection
technology: java
skill: java
topic: jvm-memory-gc
doc_type: REFERENCE
---

## Heap, stack and metaspace

Each thread has a stack of frames holding local variables, primitive values and references; objects live on the
shared heap. Class metadata lives in metaspace (native memory since Java 8, replacing PermGen). A
`StackOverflowError` comes from too deep recursion; `OutOfMemoryError: Java heap space` from a heap that cannot fit
live objects; `OutOfMemoryError: Metaspace` often from class loader leaks. Escape analysis lets the JIT allocate
objects that never escape a method on the stack or eliminate them.

## Generational garbage collection

Most objects die young (the weak generational hypothesis), so collectors split the heap into a young generation
(eden and survivor spaces) and an old generation. Minor collections copy live young objects to a survivor space and
promote objects that survive several collections. The collector finds live objects by tracing from GC roots: thread
stacks, static fields and JNI references. Objects unreachable from the roots are garbage even if they reference each
other, so reference cycles are not a problem in Java.

## Garbage collectors

G1 is the default collector: it divides the heap into regions, collects the regions with the most garbage first and
aims for a pause-time goal (`-XX:MaxGCPauseMillis`). ZGC and Shenandoah do most work concurrently with the application
and keep pauses in the low milliseconds even on very large heaps, at some throughput cost. The Parallel collector
maximises throughput for batch jobs with longer pauses. Serial suits small heaps and single-CPU containers.

## Memory leaks and diagnosis

A Java memory leak is an object that is still reachable but no longer needed: a growing static map or cache without
eviction, listeners never removed, `ThreadLocal` values in pooled threads, or unclosed resources. Symptoms are heap
usage that grows after every full GC and eventually `OutOfMemoryError`. Diagnose with GC logs (`-Xlog:gc*`), a heap
dump (`jcmd <pid> GC.heap_dump` or `-XX:+HeapDumpOnOutOfMemoryError`) analysed for dominators and retained size, and
JDK Flight Recorder for allocation profiling.

## Heap sizing in containers

`-Xms` and `-Xmx` set the initial and maximum heap. The JVM is container-aware and by default uses a quarter of the
container's memory limit for the heap; `-XX:MaxRAMPercentage` changes that. The process also needs memory for
metaspace, thread stacks, code cache and direct buffers, so setting the heap equal to the container limit gets the
process killed by the kernel's OOM killer.
