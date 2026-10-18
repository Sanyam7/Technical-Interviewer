import { Link } from 'react-router';

import type { InterviewSummary } from '../../api/types';
import { useUser } from '../auth/authContext';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { BarList } from '../../components/charts/BarList';
import { TrendChart } from '../../components/charts/TrendChart';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { ButtonLink } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/Skeleton';
import { formatScore, formatShortDate, plural } from '../../lib/format';
import { STATE_LABEL, TYPE_LABEL } from '../../lib/labels';
import { BAND_TEXT, scoreBand } from '../../lib/scoring';
import { InterviewRow } from '../history/InterviewRow';
import type { DashboardStats, NarrativeEntry } from './stats';
import { REPORT_LIMIT, useDashboardData } from './useDashboardData';

const RECENT_COUNT = 5;

function StatCard({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: string;
}) {
  return (
    <div className="border-line bg-surface shadow-raised rounded-2xl border p-5">
      <dt className="text-ink-muted text-sm font-medium">{label}</dt>
      <dd className={`mt-2 text-3xl font-bold tracking-tight tabular-nums ${tone ?? ''}`}>
        {value}
      </dd>
      {hint ? <dd className="text-ink-muted mt-1 text-xs">{hint}</dd> : null}
    </div>
  );
}

function changeText(change: number | null): string | undefined {
  if (change === null) {
    return undefined;
  }
  if (change === 0) {
    return 'Same as the interview before';
  }
  return `${change > 0 ? '+' : ''}${formatScore(change)} vs the interview before`;
}

function Stats({ stats, completed }: { stats: DashboardStats; completed: number }) {
  return (
    <dl className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Completed interviews" value={String(completed)} />
      <StatCard
        label="Average score"
        value={stats.average === null ? '—' : formatScore(stats.average)}
        tone={stats.average === null ? undefined : BAND_TEXT[scoreBand(stats.average)]}
        hint={
          stats.scored.length > 0
            ? `Across ${plural(stats.scored.length, 'scored interview')}`
            : 'No scored interview yet'
        }
      />
      <StatCard
        label="Latest score"
        value={stats.latest === null ? '—' : formatScore(stats.latest)}
        tone={stats.latest === null ? undefined : BAND_TEXT[scoreBand(stats.latest)]}
        hint={changeText(stats.change)}
      />
      <StatCard
        label="Best score"
        value={stats.best === null ? '—' : formatScore(stats.best)}
        hint={
          stats.sinceFirst === null
            ? undefined
            : `${stats.sinceFirst >= 0 ? '+' : ''}${formatScore(stats.sinceFirst)} since your first`
        }
      />
    </dl>
  );
}

function ContinueBanner({ items }: { items: InterviewSummary[] }) {
  const open = items.find((item) => !['COMPLETED', 'ABANDONED'].includes(item.state));
  if (!open) {
    return null;
  }
  return (
    <Alert
      tone="info"
      title="You have an interview in progress"
      action={
        <ButtonLink to={`/interviews/${open.id}/live`} size="sm">
          {open.state === 'INIT' ? 'Open' : 'Continue'}
        </ButtonLink>
      }
    >
      {TYPE_LABEL[open.configuration.interviewType]} ·{' '}
      {open.configuration.skills.map((skill) => skill.name).join(', ')} ({STATE_LABEL[open.state]}).
      {open.state !== 'INIT' ? ' The clock is still running.' : ''}
    </Alert>
  );
}

function NarrativeList({ items, empty }: { items: NarrativeEntry[]; empty: string }) {
  if (items.length === 0) {
    return <p className="text-ink-muted text-sm">{empty}</p>;
  }
  return (
    <ul className="space-y-4">
      {items.map((item) => (
        <li key={`${item.interviewId}-${item.text}`}>
          <p className="text-sm text-pretty">{item.text}</p>
          <Link
            to={`/interviews/${item.interviewId}/results`}
            className="text-ink-muted hover:text-ink mt-1 inline-block text-xs underline underline-offset-4"
          >
            From {formatShortDate(item.at)}
          </Link>
        </li>
      ))}
    </ul>
  );
}

function Insights({ stats }: { stats: DashboardStats }) {
  return (
    <>
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <Card
          title="Improvement over time"
          description="Overall score of each completed interview."
        >
          {stats.scored.length > 0 ? (
            <TrendChart
              label="Overall score per interview"
              points={stats.scored.map((entry) => ({
                key: entry.summary.id,
                label: formatShortDate(entry.at),
                value: entry.score,
              }))}
            />
          ) : (
            <EmptyState icon="trend" title="Nothing to chart yet">
              Scores appear here after your first completed interview.
            </EmptyState>
          )}
          {stats.scored.length === 1 ? (
            <p className="text-ink-muted mt-3 text-sm">
              Complete one more interview to see a trend.
            </p>
          ) : null}
        </Card>

        <Card
          title="Performance by technology"
          description="Average across your reports, weighted by answers."
        >
          {stats.skills.length > 0 ? (
            <BarList
              items={stats.skills.map((skill) => ({
                key: skill.slug,
                label: skill.name,
                value: skill.score,
                detail: `${plural(skill.answers, 'answer')} in ${plural(skill.interviews, 'interview')}`,
              }))}
            />
          ) : (
            <EmptyState icon="chart" title="No technology scores yet">
              They appear once a report has been scored.
            </EmptyState>
          )}
        </Card>
      </div>

      <div className="grid items-start gap-6 lg:grid-cols-3">
        <Card title="Recent strengths">
          <NarrativeList
            items={stats.strengths}
            empty="Strengths from your reports show up here."
          />
        </Card>
        <Card title="Recent weaknesses">
          <NarrativeList
            items={stats.improvements}
            empty="Areas to improve from your reports show up here."
          />
        </Card>
        <Card title="Recurring weak topics" description="Topics that scored below 45 most often.">
          {stats.weaknesses.length > 0 ? (
            <ul className="space-y-3">
              {stats.weaknesses.slice(0, 5).map((weak) => (
                <li key={weak.key} className="flex items-center justify-between gap-3 text-sm">
                  <span className="min-w-0 font-medium wrap-anywhere">{weak.name}</span>
                  <Badge tone="warning">{plural(weak.interviews, 'interview')}</Badge>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-ink-muted text-sm">No topic has scored low yet.</p>
          )}
        </Card>
      </div>
    </>
  );
}

function DashboardSkeleton() {
  return (
    <div role="status" aria-label="Loading your dashboard" className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {[0, 1, 2, 3].map((index) => (
          <Skeleton key={index} className="h-28 w-full" />
        ))}
      </div>
      <Skeleton className="h-72 w-full" />
      <Skeleton className="h-48 w-full" />
    </div>
  );
}

export function DashboardPage() {
  usePageTitle('Dashboard');
  const user = useUser();
  const data = useDashboardData();
  const { list, items, stats } = data;

  const firstName = user.displayName.trim().split(/\s+/)[0] ?? user.displayName;

  return (
    <div className="space-y-8">
      <PageHeader
        eyebrow="Dashboard"
        title={`Welcome back, ${firstName}`}
        description="Your practice history, what you are good at and what to work on next."
        action={
          <ButtonLink to="/interviews/new" size="lg" icon={<Icon name="plus" className="size-4" />}>
            New interview
          </ButtonLink>
        }
      />

      {list.isError ? (
        <ErrorState
          error={list.error}
          title="Your interviews could not be loaded"
          onRetry={() => void list.refetch()}
          retrying={list.isFetching}
        />
      ) : list.isPending ? (
        <DashboardSkeleton />
      ) : items.length === 0 ? (
        <EmptyState
          icon="sparkle"
          title="Your first interview is a few clicks away"
          action={
            <ButtonLink to="/interviews/new" size="lg">
              Set up an interview
            </ButtonLink>
          }
        >
          Choose your technologies and a difficulty. After the interview you get a scored report
          with the concepts you missed and a study plan.
        </EmptyState>
      ) : (
        <>
          <ContinueBanner items={items} />
          <Stats stats={stats} completed={data.completedCount} />

          {data.loadingReports ? (
            <div role="status" aria-label="Loading your scores" className="space-y-4">
              <Skeleton className="h-72 w-full" />
            </div>
          ) : (
            <Insights stats={stats} />
          )}
          {data.failedReports > 0 ? (
            <Alert tone="warning" title="Some reports could not be loaded">
              {plural(data.failedReports, 'report')} {data.failedReports === 1 ? 'is' : 'are'}{' '}
              missing from the numbers above. Reload the page to try again.
            </Alert>
          ) : null}
          {data.completedCount > REPORT_LIMIT ? (
            <p className="text-ink-muted text-xs">
              The charts use your latest {REPORT_LIMIT} completed interviews.
            </p>
          ) : null}

          <Card
            title="Recent interviews"
            action={
              <Link
                to="/interviews"
                className="text-brand text-sm font-semibold underline underline-offset-4"
              >
                View all {data.totalInterviews}
              </Link>
            }
            className="overflow-hidden"
          >
            <ul className="border-line -mx-5 -mb-5 border-t sm:-mx-6 sm:-mb-6">
              {items.slice(0, RECENT_COUNT).map((item) => (
                <InterviewRow key={item.id} summary={item} />
              ))}
            </ul>
          </Card>
        </>
      )}
    </div>
  );
}
