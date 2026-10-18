import type { ReactNode } from 'react';

import { ApiError } from '../../api/http';
import { useHealth, type ComponentStatus, type HealthResponse } from '../../api/health';
import { Card } from '../../components/ui/Card';
import { StatusBadge } from '../../components/ui/StatusBadge';

const SERVER_TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'medium',
});

function formatServerTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : SERVER_TIME_FORMAT.format(date);
}

function StatusValue({ status }: { status: ComponentStatus }) {
  return <StatusBadge tone={status === 'UP' ? 'success' : 'danger'}>{status}</StatusBadge>;
}

function HealthDetails({ health }: { health: HealthResponse }) {
  return (
    <dl className="mt-4 grid grid-cols-[max-content_minmax(0,1fr)] items-center gap-x-6 gap-y-3 text-sm">
      <dt className="text-ink-muted">Service</dt>
      <dd className="wrap-anywhere">{health.service}</dd>
      <dt className="text-ink-muted">Version</dt>
      <dd className="wrap-anywhere">{health.version}</dd>
      <dt className="text-ink-muted">Server time</dt>
      <dd>
        <time dateTime={health.serverTime}>{formatServerTime(health.serverTime)}</time>
      </dd>
      <dt className="text-ink-muted">Database</dt>
      <dd>
        <StatusValue status={health.database} />
      </dd>
    </dl>
  );
}

function StartBackendHelp({ error }: { error: Error }) {
  return (
    <div className="mt-4 text-sm">
      <p className="text-ink-muted">Start the database and the backend from the repository root:</p>
      <pre className="border-line bg-page mt-2 rounded-lg border p-3 font-mono text-xs wrap-anywhere whitespace-pre-wrap">
        {
          'docker compose up -d\ncd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local'
        }
      </pre>
      <p className="text-ink-muted mt-3 wrap-anywhere">
        Reason: {error.message}
        {error instanceof ApiError && error.traceId ? ` (trace ${error.traceId})` : ''}
      </p>
    </div>
  );
}

export function HealthCard() {
  const { data, error, isError, isPending, isFetching, refetch } = useHealth();

  const checkButton = (
    <button
      type="button"
      onClick={() => {
        if (!isFetching) {
          void refetch();
        }
      }}
      aria-disabled={isFetching}
      className="border-control-border bg-surface hover:bg-neutral-bg aria-disabled:hover:bg-surface min-h-11 rounded-lg border px-4 text-sm font-semibold aria-disabled:cursor-not-allowed aria-disabled:opacity-60"
    >
      {isFetching && !isPending ? 'Checking…' : 'Check again'}
    </button>
  );

  let summary: ReactNode;
  let details: ReactNode = null;

  if (isError) {
    summary = (
      <>
        <StatusBadge tone="neutral">Unreachable</StatusBadge>
        <p className="mt-3 text-sm">
          The backend cannot be reached, or it returned an unexpected response.
        </p>
      </>
    );
    details = <StartBackendHelp error={error} />;
  } else if (isPending) {
    summary = <p className="text-ink-muted text-sm">Checking backend status&hellip;</p>;
  } else if (data.status === 'UP') {
    summary = (
      <>
        <StatusBadge tone="success">Operational</StatusBadge>
        <p className="mt-3 text-sm">The backend and its database are responding.</p>
      </>
    );
    details = <HealthDetails health={data} />;
  } else {
    summary = (
      <>
        <StatusBadge tone="danger">Degraded</StatusBadge>
        <p className="mt-3 text-sm">
          The backend is running but reports that it is unhealthy. Check that PostgreSQL is running
          with <code className="font-mono">docker compose ps</code>.
        </p>
      </>
    );
    details = <HealthDetails health={data} />;
  }

  return (
    <Card title="System status" action={checkButton}>
      <div role="status" aria-live="polite">
        {summary}
      </div>
      {details}
    </Card>
  );
}
