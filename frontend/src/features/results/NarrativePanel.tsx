import type { NarrativeItem, Report } from '../../api/types';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { Icon } from '../../components/ui/Icon';
import { Markdown } from '../../components/ui/Markdown';
import { TurnLink } from './TurnLink';

export function SummaryCard({ report }: { report: Report }) {
  if (!report.summary) {
    return null;
  }
  return (
    <Card
      title="AI feedback"
      description="Written by the AI from the evaluations of your answers. It explains the scores; it does not set them."
    >
      <Markdown className="max-w-3xl text-base">{report.summary}</Markdown>
    </Card>
  );
}

function Items({ items, tone }: { items: NarrativeItem[]; tone: 'good' | 'work' }) {
  return (
    <ul className="space-y-5">
      {items.map((item) => (
        <li key={item.text} className="flex gap-3">
          <span
            className={`mt-0.5 grid size-6 shrink-0 place-items-center rounded-full ${
              tone === 'good' ? 'bg-success-bg text-success-ink' : 'bg-warning-bg text-warning-ink'
            }`}
          >
            <Icon name={tone === 'good' ? 'check' : 'trend'} className="size-3.5" />
          </span>
          <div className="min-w-0">
            <p className="text-pretty">{item.text}</p>
            {item.evidence.map((evidence) => (
              <blockquote
                key={`${evidence.questionId}-${evidence.excerpt}`}
                className="border-line text-ink-muted mt-2 border-l-2 pl-3 text-sm italic wrap-anywhere"
              >
                “{evidence.excerpt}”{' '}
                <TurnLink
                  questionId={evidence.questionId}
                  className="text-brand font-semibold not-italic underline underline-offset-4"
                />
              </blockquote>
            ))}
          </div>
        </li>
      ))}
    </ul>
  );
}

/** Strengths and weaknesses, each with the words from the answer that support it. */
export function StrengthsAndWeaknesses({ report }: { report: Report }) {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card title="Strengths">
        {report.strengths.length > 0 ? (
          <Items items={report.strengths} tone="good" />
        ) : (
          <EmptyState icon="target" title="No strengths listed">
            The written feedback was not available for this report, or no answer stood out.
          </EmptyState>
        )}
      </Card>
      <Card title="Weaknesses">
        {report.improvementAreas.length > 0 ? (
          <Items items={report.improvementAreas} tone="work" />
        ) : (
          <EmptyState icon="check" title="No weaknesses listed">
            Nothing stood out, or the written feedback was not available for this report.
          </EmptyState>
        )}
      </Card>
    </div>
  );
}
