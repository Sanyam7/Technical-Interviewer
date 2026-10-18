import { useTranscript } from '../../api/interviews';
import { Markdown } from '../../components/ui/Markdown';
import { SECTION_LABEL } from '../../lib/labels';

/** What was asked and answered so far, folded away so the open question stays the focus. */
export function EarlierTurns({
  interviewId,
  currentQuestionId,
}: {
  interviewId: string;
  currentQuestionId?: string;
}) {
  const { data } = useTranscript(interviewId);
  const turns = (data?.entries ?? []).filter(
    (entry) => entry.answer && entry.question.id !== currentQuestionId,
  );
  if (turns.length === 0) {
    return null;
  }

  return (
    <details className="border-line bg-surface group rounded-2xl border">
      <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between gap-3 px-5 py-3 text-sm font-semibold">
        <span>Earlier in this interview ({turns.length})</span>
        <span
          aria-hidden="true"
          className="text-ink-muted transition-transform group-open:rotate-180"
        >
          ▾
        </span>
      </summary>
      <ol className="border-line divide-line divide-y border-t">
        {turns.map(({ question, answer }) => (
          <li key={question.id} className="space-y-3 px-5 py-4">
            <p className="eyebrow">
              {question.isFollowUp ? 'Follow-up' : 'Question'} {question.sequenceNo} ·{' '}
              {question.topic?.name ?? SECTION_LABEL[question.section]}
            </p>
            <Markdown className="text-sm">{question.prompt}</Markdown>
            <div className="bg-surface-muted rounded-xl p-3">
              <p className="eyebrow mb-1">Your answer</p>
              {answer?.skipped ? (
                <p className="text-ink-muted text-sm italic">Skipped</p>
              ) : (
                <pre className="font-sans text-sm wrap-anywhere whitespace-pre-wrap">
                  {answer?.content}
                </pre>
              )}
            </div>
          </li>
        ))}
      </ol>
    </details>
  );
}
