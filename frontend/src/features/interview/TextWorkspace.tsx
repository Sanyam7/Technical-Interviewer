import { useId, type KeyboardEvent } from 'react';

import { ThinkingPanel } from './ThinkingPanel';
import { AnswerBar } from './AnswerBar';
import { EarlierTurns } from './EarlierTurns';
import { QuestionCard } from './QuestionCard';
import { useAnswering } from './useAnswering';
import type { WorkspaceProps } from './workspace';

/** Questions, follow-ups and the introduction: a written answer. */
export function TextWorkspace({ session, question, fresh, commands }: WorkspaceProps) {
  const answering = useAnswering({ session, question, commands });
  const labelId = useId();

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      answering.submit();
    }
  }

  return (
    <div className="space-y-5">
      <EarlierTurns interviewId={session.id} currentQuestionId={question.id} />
      <QuestionCard question={question} fresh={fresh} />

      <div className="space-y-3">
        <label htmlFor={labelId} className="block text-sm font-semibold">
          Your answer
        </label>
        <textarea
          id={labelId}
          value={answering.value}
          onChange={(event) => {
            answering.setValue(event.target.value);
          }}
          onKeyDown={onKeyDown}
          readOnly={answering.submitting}
          rows={9}
          placeholder="Write your answer. Explain your reasoning, not just the conclusion."
          className="border-control-border bg-surface text-ink placeholder:text-ink-muted/70 focus-visible:border-brand min-h-52 w-full resize-y rounded-xl border px-4 py-3 text-base leading-relaxed shadow-sm read-only:opacity-70"
        />
        {answering.submitting ? (
          <ThinkingPanel title="The interviewer is reviewing your answer" />
        ) : null}
        <AnswerBar
          length={answering.value.length}
          limit={answering.limit}
          canSubmit={answering.canSubmit}
          submitting={answering.submitting}
          skipping={answering.skipping}
          error={answering.error}
          onSubmit={answering.submit}
          onRetry={answering.retry}
          onSkip={answering.skip}
          isFollowUp={question.isFollowUp}
          hint="Tip: press Ctrl+Enter (⌘+Enter on Mac) to submit. Your draft is saved in this browser."
        />
      </div>
    </div>
  );
}
