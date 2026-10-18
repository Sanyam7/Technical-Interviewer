import { useMutation } from '@tanstack/react-query';
import { lazy, Suspense, useId } from 'react';

import { runExamples, useTranscript } from '../../api/interviews';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Icon } from '../../components/ui/Icon';
import { Card } from '../../components/ui/Card';
import { Skeleton } from '../../components/ui/Skeleton';
import { ThinkingPanel } from './ThinkingPanel';
import { AnswerBar } from './AnswerBar';
import { EarlierTurns } from './EarlierTurns';
import { QuestionCard } from './QuestionCard';
import { RunPanel } from './RunPanel';
import { useAnswering } from './useAnswering';
import type { WorkspaceProps } from './workspace';

const CodeEditor = lazy(() => import('./CodeEditor'));

/**
 * Coding: the problem on the left, the editor on the right. For a tested problem the candidate can run the visible examples
 * in the sandbox before submitting, and hidden tests decide the correctness of the submitted solution. For a problem without
 * tests (written by the AI, a language without a harness, execution off) the page says the code is reviewed by the AI only,
 * instead of offering a Run button that would do nothing.
 */
export function CodingWorkspace({ session, question, fresh, commands }: WorkspaceProps) {
  const transcript = useTranscript(session.id);
  const details = question.details;

  // A follow-up has no starter code: continue from the candidate's own last code answer. The one just sent from this page comes
  // first, because the transcript is reloaded after the turn and may not have it yet.
  const sent = commands.answer.variables;
  const justSent =
    sent &&
    !commands.answer.isError &&
    sent.question.section === 'CODING' &&
    sent.question.id !== question.id
      ? sent.content
      : undefined;
  const previousCode =
    justSent ??
    [...(transcript.data?.entries ?? [])]
      .reverse()
      .find((entry) => entry.question.section === 'CODING' && entry.answer && !entry.answer.skipped)
      ?.answer?.content ??
    '';
  const initial = details?.starterCode ?? (question.isFollowUp ? previousCode : '');

  const answering = useAnswering({ session, question, commands, initial });
  const editorLabelId = useId();
  const testable = details?.testable === true;
  const run = useMutation({
    mutationFn: (code: string) =>
      runExamples(session.id, question.id, { code, language: answering.language ?? '' }),
  });
  const runDisabled =
    answering.value.trim() === '' || run.isPending || answering.submitting || answering.skipping;

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
      <div className="space-y-5">
        <EarlierTurns interviewId={session.id} currentQuestionId={question.id} />
        <QuestionCard question={question} fresh={fresh} />

        {details && details.constraints.length > 0 ? (
          <Card title="Constraints">
            <ul className="list-disc space-y-1.5 pl-5 text-sm">
              {details.constraints.map((constraint) => (
                <li key={constraint}>{constraint}</li>
              ))}
            </ul>
          </Card>
        ) : null}

        {details && details.examples.length > 0 ? (
          <Card title="Examples">
            <ol className="space-y-4">
              {details.examples.map((example, index) => (
                <li key={`${example.input}-${index.toString()}`} className="space-y-2 text-sm">
                  <p className="eyebrow">Example {index + 1}</p>
                  <dl className="border-line bg-surface-muted grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-1.5 rounded-xl border p-3 font-mono text-[0.8125rem]">
                    <dt className="text-ink-muted">Input</dt>
                    <dd className="wrap-anywhere whitespace-pre-wrap">{example.input}</dd>
                    <dt className="text-ink-muted">Output</dt>
                    <dd className="wrap-anywhere whitespace-pre-wrap">{example.output}</dd>
                  </dl>
                  {example.explanation ? (
                    <p className="text-ink-muted">{example.explanation}</p>
                  ) : null}
                </li>
              ))}
            </ol>
          </Card>
        ) : null}
      </div>

      <div className="border-line bg-surface shadow-raised space-y-3 rounded-2xl border p-5 lg:sticky lg:top-24">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id={editorLabelId} className="flex items-center gap-2 text-sm font-semibold">
            Your solution
            {answering.language ? <Badge tone="brand">{answering.language}</Badge> : null}
          </h2>
          {details?.starterCode ? (
            <Button
              variant="ghost"
              size="sm"
              disabled={answering.submitting}
              onClick={() => {
                answering.setValue(details.starterCode ?? '');
              }}
            >
              Reset to starter code
            </Button>
          ) : null}
        </div>
        <Suspense fallback={<Skeleton className="h-[26rem] w-full" />}>
          <CodeEditor
            value={answering.value}
            onChange={answering.setValue}
            language={answering.language}
            readOnly={answering.submitting}
            onSubmit={answering.submit}
            ariaLabel="Your solution"
          />
        </Suspense>
        {testable ? (
          <p className="text-ink-muted text-xs">
            Run the examples to check your code. When you submit, it also runs against hidden tests
            (edge cases and a large input); they decide its correctness.
          </p>
        ) : (
          <Alert tone="info">
            This solution is not run here: the AI interviewer reviews your code, and its correctness
            counts as unverified.
          </Alert>
        )}
        {testable ? (
          <RunPanel
            running={run.isPending}
            result={run.data}
            error={run.error}
            onRetry={() => {
              run.mutate(answering.value);
            }}
          />
        ) : null}
        {answering.submitting ? (
          <ThinkingPanel title="Running the tests and reviewing your code" />
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
          secondaryAction={
            testable ? (
              <Button
                variant="secondary"
                disabled={runDisabled}
                loading={run.isPending}
                icon={<Icon name="play" className="size-4" />}
                onClick={() => {
                  run.mutate(answering.value);
                }}
              >
                Run examples
              </Button>
            ) : undefined
          }
          hint="Comments that explain your approach and its complexity help the review. Ctrl+Enter submits."
        />
      </div>
    </div>
  );
}
