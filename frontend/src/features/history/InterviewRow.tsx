import { Link } from 'react-router';

import { useReport } from '../../api/interviews';
import type { InterviewState, InterviewSummary } from '../../api/types';
import { Badge, type BadgeTone } from '../../components/ui/Badge';
import { Icon } from '../../components/ui/Icon';
import { formatDateTime, formatScore } from '../../lib/format';
import {
  DIFFICULTY_LABEL,
  EXPERIENCE_LABEL,
  ROLE_LABEL,
  STATE_LABEL,
  TYPE_LABEL,
} from '../../lib/labels';
import { BAND_TEXT, scoreBand } from '../../lib/scoring';
import { interviewTarget } from './interviewTarget';

const STATE_TONE: Record<InterviewState, BadgeTone> = {
  INIT: 'neutral',
  INTRODUCTION: 'brand',
  QUESTIONING: 'brand',
  FOLLOW_UP: 'brand',
  CODING: 'brand',
  SYSTEM_DESIGN: 'brand',
  FINAL_EVALUATION: 'warning',
  COMPLETED: 'success',
  ABANDONED: 'neutral',
};

function Score({ id }: { id: string }) {
  const { data } = useReport(id);
  if (data?.status !== 'READY') {
    return <span className="text-ink-muted text-sm">{data ? '—' : ''}</span>;
  }
  if (data.overallScore === null || data.overallScore === undefined) {
    return <span className="text-ink-muted text-sm">No score</span>;
  }
  return (
    <span
      className={`text-xl font-bold tracking-tight tabular-nums ${BAND_TEXT[scoreBand(data.overallScore)]}`}
    >
      {formatScore(data.overallScore)}
    </span>
  );
}

export function InterviewRow({ summary }: { summary: InterviewSummary }) {
  const { configuration } = summary;
  const target = interviewTarget(summary);
  const when = summary.endedAt ?? summary.startedAt ?? summary.createdAt;

  const content = (
    <>
      <div className="min-w-0 flex-1 basis-60">
        <p className="font-semibold tracking-tight">
          {TYPE_LABEL[configuration.interviewType]} · {ROLE_LABEL[configuration.role]},{' '}
          {EXPERIENCE_LABEL[configuration.experienceLevel]}
        </p>
        <p className="text-ink-muted mt-0.5 truncate text-sm">
          {configuration.skills.map((skill) => skill.name).join(', ')} ·{' '}
          {DIFFICULTY_LABEL[configuration.difficulty]} · {configuration.durationMinutes} min
        </p>
        <p className="text-ink-muted mt-0.5 text-xs">
          <time dateTime={when}>{formatDateTime(when)}</time>
        </p>
      </div>
      <div className="flex items-center gap-4">
        <Badge tone={STATE_TONE[summary.state]}>{STATE_LABEL[summary.state]}</Badge>
        {summary.state === 'COMPLETED' ? (
          <span className="min-w-12 text-right">
            <span className="sr-only">Score: </span>
            <Score id={summary.id} />
          </span>
        ) : null}
        {target ? (
          <span className="text-brand inline-flex items-center gap-1 text-sm font-semibold">
            {target.label}
            <Icon name="arrow-right" className="size-4" />
          </span>
        ) : null}
      </div>
    </>
  );

  const classes = 'flex flex-wrap items-center justify-between gap-x-6 gap-y-3 px-4 py-4 sm:px-5';
  return (
    <li className="border-line border-b last:border-b-0">
      {target ? (
        <Link to={target.to} className={`${classes} hover:bg-surface-muted transition-colors`}>
          {content}
        </Link>
      ) : (
        <div className={classes}>{content}</div>
      )}
    </li>
  );
}
