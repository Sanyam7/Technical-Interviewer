/**
 * The deterministic scoring rules of the interview: rubrics, score arithmetic, report aggregation and the
 * recommendation band (AI_ARCHITECTURE.md sections 2.3 and 2.8).
 *
 * <p>Pure Java, like {@code interview.engine}: no Spring, no persistence, no clock. Models only supply criterion
 * ratings from 0 to 4; every number a candidate sees is computed here from those ratings.
 */
package com.techinterviewer.evaluation;
