import { useQueries, useQuery } from '@tanstack/react-query';

import { interviewListQuery, reportQuery } from '../../api/interviews';
import { summarize } from './stats';

const LIST_SIZE = 50;
/** How many completed interviews the dashboard reads reports for: one request each, cached for good once READY. */
export const REPORT_LIMIT = 12;

/**
 * The dashboard has no endpoint of its own: it reads the interview list and the reports of the latest completed interviews,
 * the same documents the rest of the app uses.
 */
export function useDashboardData() {
  const list = useQuery(interviewListQuery({ page: 0, size: LIST_SIZE }));
  const items = list.data?.items ?? [];
  const completed = items.filter((item) => item.state === 'COMPLETED');
  const considered = completed.slice(0, REPORT_LIMIT);

  const reports = useQueries({
    queries: considered.map((item) => reportQuery(item.id)),
  });

  const loadingReports = list.isSuccess && reports.some((report) => report.isPending);
  const failedReports = reports.filter((report) => report.isError).length;
  const stats = summarize(
    considered.map((summary, index) => ({ summary, report: reports[index]?.data })),
  );

  return {
    list,
    items,
    completedCount: completed.length,
    consideredCount: considered.length,
    totalInterviews: list.data?.totalItems ?? items.length,
    loadingReports,
    failedReports,
    stats,
  };
}
