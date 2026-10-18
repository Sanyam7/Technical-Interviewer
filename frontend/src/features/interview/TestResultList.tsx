import type { CodeTestResult } from '../../api/types';
import { Badge, type BadgeTone } from '../../components/ui/Badge';
import { cx } from '../../lib/cx';

const STATUS: Record<CodeTestResult['status'], { label: string; tone: BadgeTone }> = {
  PASSED: { label: 'Passed', tone: 'success' },
  FAILED: { label: 'Wrong answer', tone: 'danger' },
  ERROR: { label: 'Error', tone: 'danger' },
  TIMEOUT: { label: 'Too slow', tone: 'warning' },
  NOT_RUN: { label: 'Not run', tone: 'neutral' },
};

const CATEGORY: Record<CodeTestResult['category'], string> = {
  EXAMPLE: 'Example',
  GENERAL: 'Hidden test',
  EDGE: 'Edge case',
  PERFORMANCE: 'Performance',
};

/**
 * One row per test. An example shows its input, the expected output and what the code returned; a hidden test shows only
 * its name, category and result (the backend never sends its data).
 */
export function TestResultList({
  results,
  className,
}: {
  results: CodeTestResult[];
  className?: string;
}) {
  return (
    <ul className={cx('divide-line border-line divide-y rounded-xl border', className)}>
      {results.map((result) => {
        const status = STATUS[result.status];
        return (
          <li key={`${result.category}-${result.name}`} className="space-y-2 px-3 py-2.5 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={status.tone}>{status.label}</Badge>
              <span className="font-semibold">{result.name}</span>
              <span className="text-ink-muted text-xs">{CATEGORY[result.category]}</span>
              {result.timeMs != null ? (
                <span className="text-ink-muted ml-auto text-xs tabular-nums">
                  {result.timeMs} ms
                </span>
              ) : null}
            </div>
            {result.input != null ? (
              <dl className="bg-surface-muted grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-1 rounded-lg p-2 font-mono text-xs">
                <dt className="text-ink-muted">Input</dt>
                <dd className="wrap-anywhere">{result.input}</dd>
                <dt className="text-ink-muted">Expected</dt>
                <dd className="wrap-anywhere">{result.expected}</dd>
                {result.actual != null ? (
                  <>
                    <dt className="text-ink-muted">Returned</dt>
                    <dd
                      className={cx(
                        'wrap-anywhere',
                        result.status !== 'PASSED' && 'text-danger-ink font-semibold',
                      )}
                    >
                      {result.actual}
                    </dd>
                  </>
                ) : null}
              </dl>
            ) : null}
            {result.message && result.status !== 'PASSED' ? (
              <p className="text-ink-muted text-xs wrap-anywhere">{result.message}</p>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
