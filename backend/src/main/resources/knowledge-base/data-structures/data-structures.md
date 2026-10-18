---
title: Data structures
technology: data-structures
doc_type: REFERENCE
---

## Arrays and dynamic arrays

An array stores elements contiguously: O(1) access by index and excellent cache locality, but O(n) insertion and
deletion in the middle. A dynamic array grows by allocating a larger array (typically 1.5 or 2 times) and copying, so
appending is amortised O(1). Two-pointer and sliding-window techniques solve many array problems in O(n).

## Linked lists

A linked list stores nodes with pointers to the next (and in a doubly linked list the previous) node. Insertion and
deletion at a known node are O(1), but finding a node by position is O(n) and memory locality is poor. Classic
techniques are a dummy head node to simplify edge cases, and fast and slow pointers to find the middle or detect a
cycle (Floyd's algorithm).

## Stacks and queues

A stack is last-in first-out (push, pop, peek in O(1)) and models recursion, undo, expression parsing and depth-first
search. A queue is first-in first-out and models breadth-first search and buffering. A deque supports both ends. A
monotonic stack keeps elements in sorted order to answer "next greater element" problems in O(n).

## Hash tables

A hash table maps keys to buckets with a hash function, giving O(1) average lookup, insertion and deletion, and O(n)
in the worst case when many keys collide. Collisions are handled by chaining (a list or tree per bucket) or open
addressing (probing for another slot). The table resizes when the load factor grows. Hash tables trade memory for speed
and do not keep order. Counting frequencies and finding complements (two-sum) are typical uses.

## Trees and binary search trees

A binary search tree keeps smaller keys in the left subtree and larger in the right, giving O(log n) search, insert
and delete when balanced, but O(n) when it degenerates into a list (for example after sorted inserts). Self-balancing
trees (AVL, red-black) guarantee O(log n). In-order traversal of a BST visits keys in sorted order. B-trees, with many
keys per node, minimise disk reads and back database indexes.

## Heaps and priority queues

A binary heap is a complete binary tree stored in an array where each parent is smaller (min-heap) or larger
(max-heap) than its children. Peek is O(1), insert and remove-top are O(log n), and building a heap from n elements is
O(n). Heaps implement priority queues, find the k largest elements in O(n log k), merge k sorted lists and drive
Dijkstra's algorithm.

## Graphs and tries

A graph is stored as an adjacency list (memory O(V + E), good for sparse graphs) or an adjacency matrix (O(V^2), O(1)
edge lookup, good for dense graphs). A trie stores strings by character along paths from the root, giving prefix
search in O(length of the word), used for autocomplete and dictionaries. A union-find (disjoint set) structure with
path compression and union by rank tracks connected components in nearly constant amortised time.
