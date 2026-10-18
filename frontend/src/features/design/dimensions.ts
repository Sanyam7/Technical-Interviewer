import type { Criterion } from '../../api/types';

/** The dimensions of the system design rubric (system-design.v2), in its order, with what each asks of a design. */
export const DESIGN_DIMENSIONS: readonly { criterion: Criterion; hint: string }[] = [
  { criterion: 'REQUIREMENTS', hint: 'What it must do, at what scale; estimates.' },
  { criterion: 'HIGH_LEVEL_DESIGN', hint: 'Components and how a request flows.' },
  { criterion: 'API_DESIGN', hint: 'Endpoints or messages and their parameters.' },
  { criterion: 'DATA_MODEL', hint: 'What is stored where; SQL or NoSQL, and why.' },
  { criterion: 'CACHING', hint: 'Where, what, and how it stays consistent.' },
  { criterion: 'MESSAGING', hint: 'Queues: what leaves the request path.' },
  { criterion: 'SCALABILITY', hint: 'What breaks first as load grows.' },
  { criterion: 'AVAILABILITY', hint: 'Redundancy, replication, failover.' },
  { criterion: 'RELIABILITY', hint: 'Nothing lost or applied twice.' },
  { criterion: 'FAILURE_HANDLING', hint: 'Timeouts, retries, degradation, recovery.' },
  { criterion: 'SECURITY', hint: 'Who may do what; data protection; abuse.' },
  { criterion: 'OBSERVABILITY', hint: 'Metrics, logs, traces, alerts.' },
  { criterion: 'TRADE_OFFS', hint: 'What you chose and what it cost.' },
];

/** Whether a criterion is a dimension of the current design rubric. */
export function isDesignDimension(criterion: Criterion): boolean {
  return DESIGN_DIMENSIONS.some((dimension) => dimension.criterion === criterion);
}
