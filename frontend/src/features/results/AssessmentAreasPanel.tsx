import type { AreaScore, AssessmentAreas } from '../../api/types';
import { Alert } from '../../components/ui/Alert';
import { Card } from '../../components/ui/Card';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { formatScore, plural } from '../../lib/format';
import { AREA_DESCRIPTION, AREA_LABEL } from '../../lib/labels';
import { BAND_TEXT, scoreBand } from '../../lib/scoring';

const BAR_TONE = {
  strong: 'success',
  proficient: 'brand',
  developing: 'warning',
  weak: 'danger',
} as const;

const CONFIDENCE_TEXT = {
  HIGH: 'high confidence',
  MEDIUM: 'medium confidence',
  LOW: 'limited evidence',
} as const;

function evidenceLine(area: AreaScore): string {
  const parts = [
    area.area === 'COMMUNICATION'
      ? plural(area.evidenceCount, 'rated answer')
      : plural(area.evidenceCount, 'answer'),
  ];
  if (area.shareOfOverall !== null && area.shareOfOverall !== undefined) {
    parts.push(`${Math.round(area.shareOfOverall * 100)}% of the overall score`);
  }
  if (area.confidence) {
    parts.push(CONFIDENCE_TEXT[area.confidence]);
  }
  return parts.join(' · ');
}

function AreaCard({ area }: { area: AreaScore }) {
  const label = AREA_LABEL[area.area];
  const scored = area.score !== null && area.score !== undefined;
  return (
    <li className="border-line bg-surface-muted/40 rounded-xl border p-4">
      <h3 className="text-sm font-semibold">{label}</h3>
      <p className="text-ink-muted text-xs">{AREA_DESCRIPTION[area.area]}</p>
      {scored ? (
        <>
          <p
            className={`mt-2 text-2xl font-bold tracking-tight tabular-nums ${BAND_TEXT[scoreBand(area.score ?? 0)]}`}
          >
            {formatScore(area.score ?? 0)}
          </p>
          <ProgressBar
            className="mt-2"
            label={`${label} score`}
            value={area.score ?? 0}
            tone={BAR_TONE[scoreBand(area.score ?? 0)]}
          />
          <p className="text-ink-muted mt-2 text-xs">{evidenceLine(area)}</p>
        </>
      ) : (
        <p className="text-warning-ink mt-2 text-sm font-medium">
          Not assessed: the interview did not get to it, or its answers could not be evaluated.
        </p>
      )}
      {area.lowConfidenceAnswers > 0 ? (
        <p className="text-ink-muted mt-1 text-xs">
          {plural(area.lowConfidenceAnswers, 'evaluation')} here{' '}
          {area.lowConfidenceAnswers === 1 ? 'was' : 'were'} unsure of{' '}
          {area.lowConfidenceAnswers === 1 ? 'itself' : 'themselves'}; worth a human review.
        </p>
      ) : null}
    </li>
  );
}

/**
 * The report by assessment area: fundamentals, practical application, problem solving, project depth and communication, each scored
 * on its own; what the interview meant to assess and did not; and why the recommendation was capped. Areas the interview did not
 * plan and has no evidence for are left out.
 */
export function AssessmentAreasPanel({ areas }: { areas: AssessmentAreas | null | undefined }) {
  if (!areas) {
    return null;
  }
  const shown = areas.areas.filter((area) => area.planned || area.evidenceCount > 0);
  if (shown.length === 0) {
    return null;
  }
  return (
    <Card
      title="What the score is made of"
      description="Each part of the interview is scored on its own, so strong answers about your projects cannot hide weak fundamentals."
    >
      <div className="space-y-5">
        {areas.recommendationCap ? (
          <Alert tone="warning" title="Recommendation capped">
            {areas.recommendationCap}
          </Alert>
        ) : null}
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {shown.map((area) => (
            <AreaCard key={area.area} area={area} />
          ))}
        </ul>
        {areas.skillsNotAssessed.length > 0 || areas.topicsNotAssessed.length > 0 ? (
          <div className="border-line rounded-xl border p-4 text-sm">
            <h3 className="font-semibold">Not assessed in this interview</h3>
            {areas.skillsNotAssessed.length > 0 ? (
              <p className="mt-1">
                <span className="text-ink-muted">Technologies: </span>
                {areas.skillsNotAssessed.map((skill) => skill.name).join(', ')}
              </p>
            ) : null}
            {areas.topicsNotAssessed.length > 0 ? (
              <p className="mt-1">
                <span className="text-ink-muted">Topics: </span>
                {areas.topicsNotAssessed.map((topic) => topic.name).join(', ')}
              </p>
            ) : null}
            <p className="text-ink-muted mt-2 text-xs">
              Nothing above says how well you know these: they were not reached, were skipped, or
              could not be evaluated.
            </p>
          </div>
        ) : null}
      </div>
    </Card>
  );
}
