import type { KnowledgeGaps } from '../../api/types';
import { BarList } from '../../components/charts/BarList';
import { Badge } from '../../components/ui/Badge';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { Tabs } from '../../components/ui/Tabs';
import { plural } from '../../lib/format';
import { TurnLink } from './TurnLink';

const BASIS_LABEL = {
  EVALUATOR_LINK: 'Linked by the evaluator',
  SHARED_SOURCE: 'Same reference material',
  SAME_WORDING: 'Same wording',
} as const;

function TopicTag({ name }: { name: string | undefined }) {
  return name ? <Badge>{name}</Badge> : null;
}

function Missed({ gaps }: { gaps: KnowledgeGaps }) {
  if (gaps.missedConcepts.length === 0) {
    return (
      <EmptyState icon="check" title="No missed concepts">
        Your answers covered the points the questions were looking for.
      </EmptyState>
    );
  }
  return (
    <ul className="divide-line divide-y">
      {gaps.missedConcepts.map((gap) => (
        <li
          key={`${gap.questionId}-${gap.concept}`}
          className="flex flex-wrap items-start justify-between gap-3 py-3.5 first:pt-0"
        >
          <div className="min-w-0 flex-1 basis-64">
            <p className="text-pretty">{gap.concept}</p>
            <div className="mt-1.5 flex flex-wrap items-center gap-2">
              <Badge tone={gap.status === 'MISSING' ? 'danger' : 'warning'}>
                {gap.status === 'MISSING' ? 'Missing' : 'Partly covered'}
              </Badge>
              <TopicTag name={gap.topic?.name} />
            </div>
          </div>
          <TurnLink questionId={gap.questionId} />
        </li>
      ))}
    </ul>
  );
}

function Misconceptions({ gaps }: { gaps: KnowledgeGaps }) {
  if (gaps.misconceptions.length === 0) {
    return (
      <EmptyState icon="check" title="No misconceptions found">
        No statement in your answers was judged to be wrong.
      </EmptyState>
    );
  }
  return (
    <ul className="space-y-4">
      {gaps.misconceptions.map((item) => (
        <li
          key={`${item.questionId}-${item.claim}`}
          className="border-line bg-surface-muted rounded-xl border p-4"
        >
          <p className="eyebrow">You said</p>
          <p className="mt-1 text-pretty">{item.claim}</p>
          <p className="eyebrow mt-3">Correction</p>
          <p className="mt-1 text-pretty">{item.correction}</p>
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
            <TopicTag name={item.topic?.name} />
            <TurnLink questionId={item.questionId} />
          </div>
        </li>
      ))}
    </ul>
  );
}

function WeakTopics({ gaps }: { gaps: KnowledgeGaps }) {
  if (gaps.weakTopics.length === 0) {
    return (
      <EmptyState icon="check" title="No weak topics">
        Every topic scored 45 or above.
      </EmptyState>
    );
  }
  return (
    <BarList
      items={gaps.weakTopics.map((weak) => ({
        key: weak.topic.key,
        label: weak.topic.name,
        value: weak.score,
        detail: plural(weak.evidenceCount, 'answer'),
      }))}
    />
  );
}

function Repeated({ gaps }: { gaps: KnowledgeGaps }) {
  if (gaps.repeatedMistakes.length === 0) {
    return (
      <EmptyState icon="check" title="No repeated mistakes">
        No gap showed up in more than one question.
      </EmptyState>
    );
  }
  return (
    <ul className="space-y-4">
      {gaps.repeatedMistakes.map((mistake) => (
        <li
          key={mistake.description + mistake.questionIds.join()}
          className="border-line rounded-xl border p-4"
        >
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={mistake.kind === 'MISCONCEPTION' ? 'danger' : 'warning'}>
              {mistake.kind === 'MISCONCEPTION' ? 'Misconception' : 'Missed concept'}
            </Badge>
            <Badge>{plural(mistake.questionIds.length, 'question')}</Badge>
            {mistake.topics.map((topic) => (
              <TopicTag key={topic.key} name={topic.name} />
            ))}
          </div>
          <p className="mt-2 text-pretty">{mistake.description}</p>
          <p className="text-ink-muted mt-2 text-xs">
            {mistake.basis.map((basis) => BASIS_LABEL[basis]).join(' · ')}
          </p>
          <p className="mt-2 flex flex-wrap gap-3">
            {mistake.questionIds.map((id, index) => (
              <TurnLink key={id} questionId={id}>{`Answer ${(index + 1).toString()}`}</TurnLink>
            ))}
          </p>
        </li>
      ))}
    </ul>
  );
}

/** What was missing, what was wrong, which topics are weak and which mistakes repeat, found without a model from the evaluations. */
export function KnowledgeGapsPanel({ gaps }: { gaps: KnowledgeGaps | null | undefined }) {
  if (!gaps) {
    return null;
  }
  return (
    <Card
      title="Knowledge gaps"
      description="Found by comparing your answers with what each question was looking for."
    >
      <Tabs
        label="Knowledge gaps"
        tabs={[
          {
            id: 'missed',
            label: 'Missed concepts',
            count: gaps.missedConcepts.length,
            panel: <Missed gaps={gaps} />,
          },
          {
            id: 'wrong',
            label: 'Misconceptions',
            count: gaps.misconceptions.length,
            panel: <Misconceptions gaps={gaps} />,
          },
          {
            id: 'weak',
            label: 'Weak topics',
            count: gaps.weakTopics.length,
            panel: <WeakTopics gaps={gaps} />,
          },
          {
            id: 'repeated',
            label: 'Repeated mistakes',
            count: gaps.repeatedMistakes.length,
            panel: <Repeated gaps={gaps} />,
          },
        ]}
      />
    </Card>
  );
}
