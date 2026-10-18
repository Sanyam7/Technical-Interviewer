---
title: Algorithms and complexity
technology: algorithms
doc_type: REFERENCE
---

## Big-O complexity

Big-O describes how running time or memory grows with input size in the worst case, ignoring constants: O(1)
constant, O(log n) logarithmic (halving each step), O(n) linear, O(n log n) for efficient comparison sorting, O(n^2)
for nested loops over the input, O(2^n) for exploring all subsets. Amortised analysis averages cost over a sequence of
operations, as for appending to a dynamic array. Space complexity counts extra memory, including the recursion stack.

## Sorting

Merge sort is O(n log n) in every case, stable, and needs O(n) extra space. Quicksort is O(n log n) on average with
small constants and sorts in place, but O(n^2) in the worst case with bad pivots; randomised or median-of-three pivots
make that unlikely. Heap sort is O(n log n) in place but not stable. Any comparison sort needs at least O(n log n)
comparisons; counting and radix sort beat that for small integer ranges. Stable sorting keeps equal elements in their
original order, which matters when sorting by several keys. Production libraries use hybrids such as Timsort.

## Binary search

Binary search finds a target in a sorted array in O(log n) by comparing with the middle element and discarding half
of the range each step. Off-by-one errors are common: keep a clear invariant for the range boundaries, and compute the
middle as `lo + (hi - lo) / 2` to avoid integer overflow. Binary search on the answer finds the smallest value that
satisfies a monotonic condition, such as the minimum capacity to ship packages within d days.

## Graph traversal and shortest paths

Breadth-first search explores level by level with a queue and finds shortest paths in unweighted graphs; depth-first
search explores as deep as possible with recursion or a stack and suits cycle detection, connected components and
topological sorting. Both are O(V + E) and must track visited nodes. Dijkstra's algorithm finds shortest paths with
non-negative weights using a priority queue in O((V + E) log V); Bellman-Ford handles negative weights. Topological
sort orders a directed acyclic graph so every edge points forward, for example to schedule dependent tasks.

## Dynamic programming

Dynamic programming solves problems with optimal substructure and overlapping subproblems by solving each subproblem
once and storing the result: top-down with memoisation or bottom-up with a table. Defining the state and the
recurrence is the key step, for example the longest common subsequence or the 0/1 knapsack. It turns exponential
recursion such as naive Fibonacci into polynomial time, and the table can often be reduced to the previous row to save
memory.

## Greedy algorithms and backtracking

A greedy algorithm makes the locally best choice at each step; it is correct only when the problem has the
greedy-choice property, as in interval scheduling by earliest end time, and must be justified with an exchange
argument. Backtracking builds candidates incrementally and abandons a partial candidate as soon as it cannot lead to a
solution, used for permutations, subsets and constraint problems such as N-Queens; pruning keeps it tractable.
