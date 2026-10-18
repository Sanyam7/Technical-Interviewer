---
title: SQL fundamentals
technology: sql
skill: postgresql
topic: sql-fundamentals
doc_type: REFERENCE
---

## Joins

An INNER JOIN returns only rows that match in both tables. A LEFT JOIN keeps every row of the left table and fills
the right side with NULLs when there is no match; putting a condition on the right table in the WHERE clause instead
of the ON clause turns it back into an inner join because NULLs fail the comparison. A FULL OUTER JOIN keeps
unmatched rows of both sides, and a CROSS JOIN produces every combination. A self-join joins a table to itself, for
example employees to their managers. Anti-joins ("rows without a match") are written with `NOT EXISTS` or a LEFT
JOIN filtered on `IS NULL`; `NOT IN` returns no rows when the subquery yields a NULL.

## Logical order of evaluation

A query is evaluated logically as FROM and JOIN, WHERE, GROUP BY, HAVING, SELECT, DISTINCT, ORDER BY, then
LIMIT/OFFSET. That is why WHERE cannot refer to a column alias or an aggregate, and HAVING filters groups after
aggregation. Aggregates such as COUNT, SUM and AVG ignore NULLs, except `COUNT(*)`, which counts rows.

## Window functions

Window functions compute a value per row over a set of related rows without collapsing them, unlike GROUP BY:
`ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY created_at DESC)` numbers each customer's orders, which solves
"latest row per group" and top-N per group. `RANK` leaves gaps after ties and `DENSE_RANK` does not. `LAG` and `LEAD`
read the previous or next row, and `SUM(amount) OVER (ORDER BY day)` computes a running total. The frame clause
(`ROWS BETWEEN 6 PRECEDING AND CURRENT ROW`) defines moving windows.

## Subqueries, CTEs and set operations

A correlated subquery references the outer query and runs per outer row conceptually; `EXISTS` stops at the first
match. Common table expressions (`WITH`) name intermediate results and make queries readable; recursive CTEs walk
hierarchies such as trees of categories. `UNION` removes duplicates (and sorts or hashes to do so), while `UNION ALL`
keeps them and is cheaper; `INTERSECT` and `EXCEPT` return common and difference rows.

## NULL semantics

NULL means unknown, so any comparison with NULL yields unknown, which WHERE treats as false: `col = NULL` never
matches and you must use `IS NULL`. `COALESCE` returns the first non-null argument. In PostgreSQL `IS DISTINCT FROM`
compares treating NULLs as equal values.
