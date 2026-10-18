import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';

import { ApiError } from '../../api/http';
import {
  interviewKeys,
  retryReport,
  useReport,
  useSession,
  useTranscript,
} from '../../api/interviews';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { Alert } from '../../components/ui/Alert';
import { Button, ButtonLink } from '../../components/ui/Button';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { Skeleton } from '../../components/ui/Skeleton';
import { ThinkingPanel } from '../interview/ThinkingPanel';
import { AssessmentAreasPanel } from './AssessmentAreasPanel';
import { KnowledgeGapsPanel } from './KnowledgeGapsPanel';
import { IntegrityNotes, StudyPlan } from './StudyPlan';
import { StrengthsAndWeaknesses, SummaryCard } from './NarrativePanel';
import { ReportHero } from './ReportHero';
import { SectionScores } from './SectionScores';
import { SkillBreakdown } from './SkillBreakdown';
import { DesignScorecard } from './DesignScorecard';
import { Timeline } from './Timeline';
import type { Report } from '../../api/types';

const JUMP_LINKS = [
  ['#summary', 'Summary'],
  ['#skills', 'Topics'],
  ['#gaps', 'Knowledge gaps'],
  ['#plan', 'Study plan'],
  ['#timeline', 'Timeline'],
] as const;

function ReportSkeleton() {
  return (
    <div role="status" aria-label="Loading the report" className="space-y-5">
      <Skeleton className="h-10 w-64" />
      <Skeleton className="h-56 w-full" />
      <Skeleton className="h-32 w-full" />
    </div>
  );
}

function ReadyReport({ report, interviewId }: { report: Report; interviewId: string }) {
  const session = useSession(interviewId);
  const transcript = useTranscript(interviewId);

  return (
    <div className="space-y-6">
      <ReportHero report={report} session={session.data} />
      <IntegrityNotes report={report} />
      <SectionScores report={report} />
      <AssessmentAreasPanel areas={report.assessmentAreas} />

      <nav aria-label="Report sections" className="flex flex-wrap gap-2">
        {JUMP_LINKS.map(([href, label]) => (
          <a
            key={href}
            href={href}
            className="border-line bg-surface hover:bg-surface-muted inline-flex min-h-9 items-center rounded-full border px-3.5 text-sm font-semibold"
          >
            {label}
          </a>
        ))}
      </nav>

      <div id="summary" className="scroll-mt-6 space-y-6">
        <SummaryCard report={report} />
        <StrengthsAndWeaknesses report={report} />
      </div>
      <div id="skills" className="scroll-mt-6">
        <SkillBreakdown report={report} />
      </div>
      <div id="gaps" className="scroll-mt-6">
        <KnowledgeGapsPanel gaps={report.knowledgeGaps} />
      </div>
      <div id="plan" className="scroll-mt-6">
        <StudyPlan report={report} />
      </div>
      <DesignScorecard transcript={transcript.data} />
      <div id="timeline" className="scroll-mt-6">
        {transcript.isError ? (
          <ErrorState
            error={transcript.error}
            title="The timeline could not be loaded"
            onRetry={() => void transcript.refetch()}
          />
        ) : (
          <Timeline transcript={transcript.data} />
        )}
      </div>
    </div>
  );
}

export function ResultsPage() {
  const { id = '' } = useParams();
  usePageTitle('Interview results');
  const queryClient = useQueryClient();
  const report = useReport(id);

  const retry = useMutation({
    mutationFn: () => retryReport(id),
    onSuccess: (next) => {
      queryClient.setQueryData(interviewKeys.report(id), next);
    },
  });

  let body;
  // A poll that fails while a report is shown or awaited keeps what the page has; the next poll tries again.
  if (report.isError && !report.data) {
    const error = report.error;
    if (error instanceof ApiError && error.code === 'INTERVIEW_NOT_FINISHED') {
      body = (
        <div className="space-y-4">
          <ErrorState error={error} title="This interview is not finished" />
          <ButtonLink to={`/interviews/${id}/live`}>Return to the interview</ButtonLink>
        </div>
      );
    } else {
      body = (
        <ErrorState
          error={error}
          title="The report could not be loaded"
          onRetry={() => void report.refetch()}
          retrying={report.isFetching}
        />
      );
    }
  } else if (!report.data) {
    body = <ReportSkeleton />;
  } else if (report.data.status === 'PENDING' || report.data.status === 'GENERATING') {
    body = (
      <ThinkingPanel title="Your report is being written">
        The scores are computed and the written feedback is generated. This page updates by itself.
      </ThinkingPanel>
    );
  } else if (report.data.status === 'FAILED') {
    body = (
      <div className="space-y-4">
        <Alert
          tone="danger"
          title="The report could not be made"
          action={
            report.data.retryable ? (
              <Button
                variant="secondary"
                size="sm"
                loading={retry.isPending}
                onClick={() => {
                  retry.mutate();
                }}
              >
                Try again
              </Button>
            ) : undefined
          }
        >
          {report.data.errorMessage ?? 'Something went wrong while making the report.'}
        </Alert>
        {retry.isError ? <ErrorState error={retry.error} /> : null}
      </div>
    );
  } else {
    body = <ReadyReport report={report.data} interviewId={id} />;
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link
          to="/interviews"
          className="text-ink-muted hover:text-ink inline-flex items-center gap-1.5 text-sm font-semibold"
        >
          <Icon name="arrow-left" className="size-4" />
          All interviews
        </Link>
        <ButtonLink
          to="/interviews/new"
          variant="secondary"
          size="sm"
          icon={<Icon name="plus" className="size-4" />}
        >
          Practise again
        </ButtonLink>
      </div>
      <h1 className="text-headline">Interview results</h1>
      {body}
    </div>
  );
}
