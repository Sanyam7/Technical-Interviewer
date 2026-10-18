import { useSearchParams } from 'react-router';

import { useInterviewList } from '../../api/interviews';
import type { InterviewState } from '../../api/types';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { Button, ButtonLink } from '../../components/ui/Button';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { SelectField } from '../../components/ui/Field';
import { Icon } from '../../components/ui/Icon';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/Skeleton';
import { STATE_LABEL } from '../../lib/labels';
import { InterviewRow } from './InterviewRow';

const PAGE_SIZE = 10;
const FILTERS: ('ALL' | InterviewState)[] = ['ALL', 'COMPLETED', 'INIT', 'ABANDONED'];

function parseFilter(value: string | null): 'ALL' | InterviewState {
  return FILTERS.find((filter) => filter === value) ?? 'ALL';
}

export function HistoryPage() {
  usePageTitle('Interview history');
  const [params, setParams] = useSearchParams();
  const filter = parseFilter(params.get('state'));
  // The URL holds the filter and the page, so a reload or the back button returns to the same list.
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);

  function show(next: { state: 'ALL' | InterviewState; page: number }) {
    const search: Record<string, string> = {};
    if (next.state !== 'ALL') {
      search.state = next.state;
    }
    if (next.page > 0) {
      search.page = String(next.page);
    }
    setParams(search);
  }

  const query = useInterviewList({
    page,
    size: PAGE_SIZE,
    state: filter === 'ALL' ? undefined : filter,
  });
  const items = query.data?.items ?? [];
  const totalPages = query.data?.totalPages ?? 0;

  return (
    <div className="space-y-8">
      <PageHeader
        eyebrow="History"
        title="Your interviews"
        description="Every interview you have started, newest first."
        action={
          <ButtonLink to="/interviews/new" icon={<Icon name="plus" className="size-4" />}>
            New interview
          </ButtonLink>
        }
      />

      <SelectField
        label="Show"
        value={filter}
        fieldClassName="max-w-56"
        onChange={(event) => {
          show({ state: parseFilter(event.target.value), page: 0 });
        }}
      >
        {FILTERS.map((value) => (
          <option key={value} value={value}>
            {value === 'ALL' ? 'All interviews' : STATE_LABEL[value]}
          </option>
        ))}
      </SelectField>

      {query.isError ? (
        <ErrorState
          error={query.error}
          title="Your interviews could not be loaded"
          onRetry={() => void query.refetch()}
          retrying={query.isFetching}
        />
      ) : query.isPending ? (
        <div role="status" aria-label="Loading your interviews" className="space-y-3">
          {[0, 1, 2, 3].map((index) => (
            <Skeleton key={index} className="h-20 w-full" />
          ))}
        </div>
      ) : items.length === 0 && page > 0 ? (
        <EmptyState
          icon="history"
          title="This page is empty"
          action={
            <Button
              variant="secondary"
              onClick={() => {
                show({ state: filter, page: 0 });
              }}
            >
              Go to the first page
            </Button>
          }
        >
          The list has fewer pages than this address asks for.
        </EmptyState>
      ) : items.length === 0 ? (
        <EmptyState
          icon="history"
          title={filter === 'ALL' ? 'No interviews yet' : 'No interviews match'}
          action={
            filter === 'ALL' ? (
              <ButtonLink to="/interviews/new">Start your first interview</ButtonLink>
            ) : (
              <Button
                variant="secondary"
                onClick={() => {
                  show({ state: 'ALL', page: 0 });
                }}
              >
                Show all
              </Button>
            )
          }
        >
          {filter === 'ALL'
            ? 'Interviews you take appear here with their scores.'
            : 'Try another filter.'}
        </EmptyState>
      ) : (
        <>
          <ul
            aria-label="Interviews"
            aria-busy={query.isFetching}
            className="border-line bg-surface shadow-raised overflow-hidden rounded-2xl border"
          >
            {items.map((item) => (
              <InterviewRow key={item.id} summary={item} />
            ))}
          </ul>
          {totalPages > 1 ? (
            <nav aria-label="Pages" className="flex items-center justify-between gap-3">
              <Button
                variant="secondary"
                disabled={page === 0}
                onClick={() => {
                  show({ state: filter, page: page - 1 });
                }}
              >
                Previous
              </Button>
              <p className="text-ink-muted text-sm tabular-nums">
                Page {page + 1} of {totalPages}
              </p>
              <Button
                variant="secondary"
                disabled={page + 1 >= totalPages}
                onClick={() => {
                  show({ state: filter, page: page + 1 });
                }}
              >
                Next
              </Button>
            </nav>
          ) : null}
        </>
      )}
    </div>
  );
}
