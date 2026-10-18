import type {
  CodeAnalysisFeedback,
  CodeTestsFeedback,
  CriterionFeedback,
  Transcript,
  TranscriptEntry,
} from '../../api/types';
import { Badge, type BadgeTone } from '../../components/ui/Badge';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { Icon } from '../../components/ui/Icon';
import { ResumeQuote } from '../interview/QuestionCard';
import { Markdown } from '../../components/ui/Markdown';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { CRITERION_LABEL, SECTION_LABEL } from '../../lib/labels';
import { formatScore } from '../../lib/format';
import { ratingShare, scoreBand } from '../../lib/scoring';
import { ArchitectureDiagramView } from '../design/ArchitectureDiagramView';
import { TestResultList } from '../interview/TestResultList';

const BAND_TONE: Record<ReturnType<typeof scoreBand>, BadgeTone> = {
  strong: 'success',
  proficient: 'brand',
  developing: 'warning',
  weak: 'danger',
};

const KEY_POINT: Record<'COVERED' | 'PARTIAL' | 'MISSING', { label: string; tone: BadgeTone }> = {
  COVERED: { label: 'Covered', tone: 'success' },
  PARTIAL: { label: 'Partly', tone: 'warning' },
  MISSING: { label: 'Missed', tone: 'danger' },
};

const BASIS: Record<CriterionFeedback['basis'], { label: string; tone: BadgeTone } | null> = {
  MODEL: null,
  TESTS: { label: 'Set by tests', tone: 'brand' },
  CAPPED: { label: 'Adjusted', tone: 'warning' },
};

function Criterion({ item }: { item: CriterionFeedback }) {
  const basis = BASIS[item.basis];
  const tone =
    item.rating >= 3
      ? 'success'
      : item.rating >= 2
        ? 'brand'
        : item.rating >= 1
          ? 'warning'
          : 'danger';
  return (
    <li>
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="flex flex-wrap items-center gap-2 font-semibold">
          {CRITERION_LABEL[item.criterion]}
          {basis ? <Badge tone={basis.tone}>{basis.label}</Badge> : null}
        </span>
        <span className="tabular-nums">{item.rating} / 4</span>
      </div>
      <ProgressBar
        className="mt-1.5"
        label={`${CRITERION_LABEL[item.criterion]} rating`}
        value={ratingShare(item.rating)}
        tone={tone}
      />
      {item.note ? <p className="mt-1.5 text-sm font-medium text-pretty">{item.note}</p> : null}
      {item.rationale ? (
        <p className="text-ink-muted mt-1 text-sm text-pretty">{item.rationale}</p>
      ) : null}
      {item.evidence.map((quote) => (
        <blockquote
          key={quote}
          className="border-line text-ink-muted mt-1 border-l-2 pl-2 text-sm wrap-anywhere"
        >
          “{quote}”
        </blockquote>
      ))}
    </li>
  );
}

function CodeAnalysis({ analysis }: { analysis: CodeAnalysisFeedback }) {
  return (
    <div>
      <h4 className="eyebrow">Algorithm and complexity</h4>
      <p className="mt-2 text-sm text-pretty">{analysis.approach}</p>
      <dl className="mt-3 grid gap-3 sm:grid-cols-2">
        <div className="border-line rounded-xl border p-3">
          <dt className="text-ink-muted text-xs">Your solution</dt>
          <dd className="mt-1 font-mono text-sm">
            {analysis.timeComplexity} time · {analysis.spaceComplexity} space
          </dd>
        </div>
        {analysis.referenceTimeComplexity ? (
          <div className="border-line rounded-xl border p-3">
            <dt className="text-ink-muted text-xs">Reference solution</dt>
            <dd className="mt-1 font-mono text-sm">
              {analysis.referenceTimeComplexity} time · {analysis.referenceSpaceComplexity} space
            </dd>
          </div>
        ) : null}
      </dl>
    </div>
  );
}

function CodeTests({ tests }: { tests: CodeTestsFeedback }) {
  return (
    <div>
      <h4 className="eyebrow">Tests</h4>
      <p className="mt-2 text-sm">
        <span className="font-semibold">
          {tests.passed} of {tests.total} tests passed
        </span>
        <span className="text-ink-muted">
          {' '}
          · run in {tests.language} in an isolated sandbox · hidden tests show their name only
        </span>
      </p>
      {tests.compileOutput ? (
        <pre className="bg-surface-muted mt-2 max-h-48 overflow-auto rounded-lg p-2 font-mono text-xs whitespace-pre-wrap">
          {tests.compileOutput}
        </pre>
      ) : null}
      <TestResultList results={tests.results} className="mt-3" />
    </div>
  );
}

function Evaluation({ entry }: { entry: TranscriptEntry }) {
  const feedback = entry.feedback;
  if (!feedback) {
    return null;
  }
  return (
    <div className="space-y-5">
      <p className="text-pretty">{feedback.summary}</p>
      {feedback.codeTests ? <CodeTests tests={feedback.codeTests} /> : null}
      {feedback.codeAnalysis ? <CodeAnalysis analysis={feedback.codeAnalysis} /> : null}
      {feedback.criteria.length > 0 ? (
        <div>
          <h4 className="eyebrow">Ratings</h4>
          <ul className="mt-3 grid gap-x-8 gap-y-4 sm:grid-cols-2">
            {feedback.criteria.map((item) => (
              <Criterion key={item.criterion} item={item} />
            ))}
          </ul>
        </div>
      ) : null}
      {feedback.keyPoints.length > 0 ? (
        <div>
          <h4 className="eyebrow">What the question looked for</h4>
          <ul className="mt-3 space-y-2">
            {feedback.keyPoints.map((point) => (
              <li key={point.text} className="flex flex-wrap items-start gap-2 text-sm">
                <Badge tone={KEY_POINT[point.status].tone}>{KEY_POINT[point.status].label}</Badge>
                <span className="min-w-0 flex-1 basis-60 text-pretty">{point.text}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function Turn({ entry }: { entry: TranscriptEntry }) {
  const { question, answer, feedback } = entry;
  const isCode = question.answerFormat === 'CODE';

  return (
    <li
      id={`turn-${question.id}`}
      tabIndex={-1}
      className="relative scroll-mt-6 pb-8 pl-8 outline-none last:pb-0"
    >
      <span
        aria-hidden="true"
        className="bg-surface border-brand absolute top-1 left-0 grid size-5 place-items-center rounded-full border-2"
      />
      <details className="group border-line bg-surface rounded-2xl border">
        <summary className="flex min-h-14 cursor-pointer list-none flex-wrap items-center gap-x-3 gap-y-1.5 px-4 py-3">
          <span className="min-w-0 flex-1 basis-56">
            <span className="eyebrow block">
              {question.isFollowUp ? 'Follow-up' : 'Question'} {question.sequenceNo} ·{' '}
              {question.topic?.name ?? SECTION_LABEL[question.section]}
              {question.design?.probe
                ? ` · probing ${CRITERION_LABEL[question.design.probe].toLowerCase()}`
                : ''}
              {question.resume ? ' · from your résumé' : ''}
            </span>
            <span className="mt-0.5 line-clamp-2 text-sm font-medium text-pretty">
              {question.prompt.replaceAll('`', '')}
            </span>
          </span>
          {feedback ? (
            <Badge tone={BAND_TONE[scoreBand(feedback.overallScore)]}>
              {formatScore(feedback.overallScore)}
            </Badge>
          ) : answer?.skipped ? (
            <Badge>Skipped</Badge>
          ) : answer ? (
            <Badge tone="warning">Not scored</Badge>
          ) : (
            <Badge>Unanswered</Badge>
          )}
          <Icon
            name="arrow-right"
            className="text-ink-muted size-4 transition-transform group-open:rotate-90"
          />
        </summary>
        <div className="border-line space-y-6 border-t px-4 py-5">
          <div>
            <h4 className="eyebrow">Question</h4>
            <Markdown className="mt-2 text-sm">{question.prompt}</Markdown>
            {question.resume ? <ResumeQuote reference={question.resume} /> : null}
          </div>
          <div>
            <h4 className="eyebrow">Your answer</h4>
            {answer ? (
              answer.skipped ? (
                <p className="text-ink-muted mt-2 text-sm italic">You skipped this question.</p>
              ) : (
                <pre
                  className={`bg-surface-muted mt-2 max-h-96 overflow-auto rounded-xl p-3 text-sm wrap-anywhere whitespace-pre-wrap ${
                    isCode ? 'font-mono text-[0.8125rem]' : 'font-sans'
                  }`}
                >
                  {answer.content}
                </pre>
              )
            ) : (
              <p className="text-ink-muted mt-2 text-sm italic">This question was not answered.</p>
            )}
            {answer?.architecture ? (
              <ArchitectureDiagramView diagram={answer.architecture} className="mt-3" />
            ) : null}
          </div>
          {feedback ? (
            <Evaluation entry={entry} />
          ) : answer && !answer.skipped ? (
            <p className="text-ink-muted text-sm">
              This answer has no evaluation, so it is not part of the scores.
            </p>
          ) : null}
        </div>
      </details>
    </li>
  );
}

/** Every question of the interview in order, with your answer and how it was rated. */
export function Timeline({ transcript }: { transcript: Transcript | undefined }) {
  const entries = transcript?.entries ?? [];
  return (
    <Card
      title="Interview timeline"
      description="Every question in the order it was asked. Open one to see your answer and how it was rated."
    >
      {entries.length === 0 ? (
        <EmptyState icon="history" title="No questions were asked">
          The interview ended before the first question.
        </EmptyState>
      ) : (
        <ol className="before:bg-line relative before:absolute before:top-3 before:bottom-3 before:left-[9px] before:w-0.5">
          {entries.map((entry) => (
            <Turn key={entry.question.id} entry={entry} />
          ))}
        </ol>
      )}
    </Card>
  );
}
