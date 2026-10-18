import type { CriterionFeedback, Transcript } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Card } from '../../components/ui/Card';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { formatScore } from '../../lib/format';
import { CRITERION_LABEL } from '../../lib/labels';
import { ratingShare } from '../../lib/scoring';
import { ArchitectureDiagramView } from '../design/ArchitectureDiagramView';
import { latestSubmitted } from '../design/useDiagramDraft';

function tone(rating: number): 'success' | 'brand' | 'warning' | 'danger' {
  return rating >= 3 ? 'success' : rating >= 2 ? 'brand' : rating >= 1 ? 'warning' : 'danger';
}

/**
 * The system design section at a glance: the design was rated as a whole after every answer, so the last evaluated answer of
 * the thread holds the final rating of each dimension. Shows them with the words they rest on, which dimensions the
 * interviewer probed, and the final architecture diagram.
 */
export function DesignScorecard({ transcript }: { transcript: Transcript | undefined }) {
  const design = (transcript?.entries ?? []).filter(
    (entry) => entry.question.section === 'SYSTEM_DESIGN',
  );
  const last = [...design].reverse().find((entry) => entry.feedback);
  if (!last?.feedback) {
    return null;
  }
  const probed = design
    .map((entry) => entry.question.design?.probe)
    .filter((probe) => probe != null);
  const diagram = latestSubmitted(design);
  const problem = design.find((entry) => !entry.question.isFollowUp)?.question;
  const criteria: CriterionFeedback[] = last.feedback.criteria;

  return (
    <Card
      title="System design scorecard"
      description={`${problem?.design?.title ?? 'The design problem'}: every dimension rated on the whole discussion, ${formatScore(last.feedback.overallScore)} overall. A rating above 1 rests on your own words, quoted below.`}
    >
      {probed.length > 0 ? (
        <p className="mb-4 text-sm">
          <span className="font-semibold">The interviewer probed, in order: </span>
          {probed.map((probe) => CRITERION_LABEL[probe]).join(' → ')}
        </p>
      ) : null}
      <ul className="grid gap-x-8 gap-y-4 sm:grid-cols-2">
        {criteria.map((item) => (
          <li key={item.criterion}>
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span className="flex flex-wrap items-center gap-2 font-semibold">
                {CRITERION_LABEL[item.criterion]}
                {probed.includes(item.criterion) ? <Badge tone="brand">Probed</Badge> : null}
                {item.basis === 'CAPPED' ? <Badge tone="warning">Adjusted</Badge> : null}
              </span>
              <span className="tabular-nums">{item.rating} / 4</span>
            </div>
            <ProgressBar
              className="mt-1.5"
              label={`${CRITERION_LABEL[item.criterion]} rating`}
              value={ratingShare(item.rating)}
              tone={tone(item.rating)}
            />
            {item.evidence[0] ? (
              <blockquote className="border-line text-ink-muted mt-1.5 border-l-2 pl-2 text-sm wrap-anywhere">
                “{item.evidence[0]}”
              </blockquote>
            ) : (
              <p className="text-ink-muted mt-1.5 text-sm italic">Not shown in your design.</p>
            )}
          </li>
        ))}
      </ul>
      {diagram ? (
        <div className="mt-6">
          <h3 className="eyebrow mb-2">Your final architecture</h3>
          <ArchitectureDiagramView diagram={diagram} />
        </div>
      ) : null}
    </Card>
  );
}
