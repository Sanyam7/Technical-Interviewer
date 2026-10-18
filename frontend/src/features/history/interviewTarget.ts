import type { InterviewSummary } from '../../api/types';

/** Where a click on an interview leads: the report when it is done, the room when it can continue, nowhere when abandoned. */
export function interviewTarget(summary: InterviewSummary): { to: string; label: string } | null {
  switch (summary.state) {
    case 'COMPLETED':
      return { to: `/interviews/${summary.id}/results`, label: 'View results' };
    case 'ABANDONED':
      return null;
    case 'INIT':
      return { to: `/interviews/${summary.id}/live`, label: 'Open' };
    default:
      return { to: `/interviews/${summary.id}/live`, label: 'Continue' };
  }
}
