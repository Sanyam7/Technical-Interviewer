import type { Report, Session } from '../../api/types';
import { ScoreRing } from '../../components/charts/ScoreRing';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Icon } from '../../components/ui/Icon';
import { formatDate, plural } from '../../lib/format';
import {
  DIFFICULTY_LABEL,
  EXPERIENCE_LABEL,
  RECOMMENDATION_LABEL,
  RECOMMENDATION_MEANING,
  ROLE_LABEL,
  TYPE_LABEL,
} from '../../lib/labels';
import { RECOMMENDATION_TONE } from '../../lib/scoring';

function Stat({ value, label }: { value: number; label: string }) {
  return (
    <div>
      <dd className="text-2xl font-bold tracking-tight tabular-nums">{value}</dd>
      <dt className="text-ink-muted text-xs">{label}</dt>
    </div>
  );
}

function OutcomeNotice({ report }: { report: Report }) {
  const assessment = report.assessment;
  if (!assessment || report.outcome === 'COMPLETE') {
    return null;
  }
  const percent = Math.floor(assessment.assessedShare * 100);
  const count = assessment.unassessableAnswers.length;
  if (report.outcome === 'INCONCLUSIVE') {
    return (
      <Alert tone="warning" title="Not enough could be assessed for a verdict">
        Only {percent}% of the interview could be evaluated, so there is no overall score or
        recommendation. This says nothing about how you did.
      </Alert>
    );
  }
  return (
    <Alert tone="info" title="Scored on most, not all, of your answers">
      {plural(count, 'answer')} could not be evaluated by the AI service ({percent}% of the
      interview was assessed). The scores below use the answers that were; the others were not
      counted as zero.
    </Alert>
  );
}

/** The first screen of the report: the verdict, and how much it stands on. */
export function ReportHero({ report, session }: { report: Report; session: Session | undefined }) {
  const { overallScore, recommendation, coverage } = report;
  const configuration = session?.configuration;

  return (
    <div className="space-y-4">
      <section
        aria-labelledby="verdict-title"
        className="border-line bg-surface shadow-raised bg-hero relative overflow-hidden rounded-3xl border p-6 sm:p-8"
      >
        <div className="flex flex-wrap items-center gap-x-10 gap-y-6">
          {overallScore !== null && overallScore !== undefined ? (
            <ScoreRing score={overallScore} label="Overall score" />
          ) : (
            <div className="border-line text-ink-muted grid size-40 place-items-center rounded-full border-4 border-dashed text-center text-sm font-medium">
              No overall
              <br />
              score
            </div>
          )}
          <div className="min-w-0 flex-1 basis-72">
            <p className="eyebrow">Overall result</p>
            <h2 id="verdict-title" className="text-headline mt-1">
              {recommendation ? RECOMMENDATION_LABEL[recommendation] : 'No recommendation'}
            </h2>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              {recommendation ? (
                <Badge tone={RECOMMENDATION_TONE[recommendation]}>Hiring signal</Badge>
              ) : null}
              {configuration ? (
                <>
                  <Badge>{TYPE_LABEL[configuration.interviewType]}</Badge>
                  <Badge>
                    {ROLE_LABEL[configuration.role]} ·{' '}
                    {EXPERIENCE_LABEL[configuration.experienceLevel]}
                  </Badge>
                  <Badge>{DIFFICULTY_LABEL[configuration.difficulty]}</Badge>
                </>
              ) : null}
            </div>
            <p className="text-ink-muted mt-3 max-w-xl text-pretty">
              {recommendation
                ? RECOMMENDATION_MEANING[recommendation]
                : overallScore === null || overallScore === undefined
                  ? 'There was not enough evidence to give a score.'
                  : 'The result stands on too little evidence for a recommendation.'}
            </p>
            <p className="text-ink-muted mt-2 flex items-start gap-1.5 text-xs">
              <Icon name="shield" className="mt-0.5 size-3.5 shrink-0" />A practice-interview signal
              from an AI rubric, not a hiring decision.
            </p>
          </div>
          {coverage ? (
            <dl className="border-line grid w-full grid-cols-4 gap-4 border-t pt-5 sm:w-auto sm:border-t-0 sm:border-l sm:pt-0 sm:pl-8">
              <Stat value={coverage.questionsAnswered} label="Answered" />
              <Stat value={coverage.skipped} label="Skipped" />
              <Stat value={coverage.followUps} label="Follow-ups" />
              <Stat value={coverage.minutesUsed} label="Minutes" />
            </dl>
          ) : null}
        </div>
        {session?.startedAt ? (
          <p className="text-ink-muted mt-5 text-xs">Taken on {formatDate(session.startedAt)}</p>
        ) : null}
      </section>
      <OutcomeNotice report={report} />
    </div>
  );
}
