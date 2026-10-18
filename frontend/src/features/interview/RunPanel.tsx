import type { CodeRunResult } from '../../api/types';
import { Alert } from '../../components/ui/Alert';
import { ErrorState } from '../../components/ui/ErrorState';
import { Spinner } from '../../components/ui/Spinner';
import { TestResultList } from './TestResultList';

const RUN_STATUS: Record<CodeRunResult['status'], string> = {
  COMPLETED: '',
  COMPILATION_ERROR: 'The code did not compile.',
  TIMED_OUT: 'The run exceeded its time limit.',
  CRASHED: 'The program stopped before every example ran (for example, out of memory).',
  OUTPUT_LIMIT: 'The code printed too much output; the run was stopped.',
};

interface RunPanelProps {
  running: boolean;
  result: CodeRunResult | undefined;
  error: unknown;
  onRetry: () => void;
}

/** What the last "Run examples" showed. Nothing here is graded: the submitted answer is tested again on every test. */
export function RunPanel({ running, result, error, onRetry }: RunPanelProps) {
  if (running) {
    return (
      <div role="status" className="text-ink-muted flex items-center gap-2 text-sm">
        <Spinner size="sm" />
        Running your code against the examples in an isolated sandbox…
      </div>
    );
  }
  if (error !== null && error !== undefined) {
    return <ErrorState error={error} title="The examples could not be run" onRetry={onRetry} />;
  }
  if (!result) {
    return null;
  }
  const allPassed = result.status === 'COMPLETED' && result.passed === result.total;
  return (
    <section aria-label="Example results" className="space-y-3">
      <p role="status" className="text-sm font-semibold">
        {result.passed} of {result.total} examples passed
        <span className="text-ink-muted ml-2 font-normal tabular-nums">
          {(result.durationMs / 1000).toFixed(1)} s
        </span>
      </p>
      {RUN_STATUS[result.status] ? (
        <Alert tone="warning" title={RUN_STATUS[result.status]}>
          {result.compileOutput ? (
            <pre className="mt-1 max-h-48 overflow-auto font-mono text-xs whitespace-pre-wrap">
              {result.compileOutput}
            </pre>
          ) : null}
        </Alert>
      ) : allPassed ? (
        <Alert tone="success" title="Every example passed.">
          Hidden tests, including edge cases and a large input, run when you submit.
        </Alert>
      ) : null}
      {result.status !== 'COMPILATION_ERROR' ? <TestResultList results={result.results} /> : null}
      {result.output ? (
        <details className="text-sm">
          <summary className="text-ink-muted cursor-pointer">What your code printed</summary>
          <pre className="bg-surface-muted mt-2 max-h-40 overflow-auto rounded-lg p-2 font-mono text-xs whitespace-pre-wrap">
            {result.output}
          </pre>
        </details>
      ) : null}
    </section>
  );
}
