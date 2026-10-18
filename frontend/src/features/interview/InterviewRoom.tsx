import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router';

import { ApiError } from '../../api/http';
import { useSession } from '../../api/interviews';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { Alert } from '../../components/ui/Alert';
import { Button, ButtonLink } from '../../components/ui/Button';
import { Dialog } from '../../components/ui/Dialog';
import { ErrorState } from '../../components/ui/ErrorState';
import { Skeleton } from '../../components/ui/Skeleton';
import { workspacePath } from '../../lib/labels';
import { Briefing } from './Briefing';
import { CodingWorkspace } from './CodingWorkspace';
import { DesignWorkspace } from './DesignWorkspace';
import { ProgressPanel } from './ProgressPanel';
import { RoomHeader } from './RoomHeader';
import { TextWorkspace } from './TextWorkspace';
import { ThinkingPanel } from './ThinkingPanel';
import { useTimeUp } from './useCountdown';
import { useRoomCommands, type RoomCommands } from './useRoomCommands';
import { WrapUp } from './WrapUp';
import { roomPhase } from './roomPhase';
import type { Question, Session } from '../../api/types';

function RoomSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading the interview"
      className="mx-auto w-full max-w-5xl space-y-4 p-6"
    >
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-40 w-full" />
      <Skeleton className="h-56 w-full" />
    </div>
  );
}

function Workspace({
  session,
  question,
  fresh,
  commands,
}: {
  session: Session;
  question: Question;
  fresh: boolean;
  commands: RoomCommands;
}) {
  const props = { session, question, fresh, commands };
  switch (question.section) {
    case 'CODING':
      return <CodingWorkspace key={question.id} {...props} />;
    case 'SYSTEM_DESIGN':
      return <DesignWorkspace key={question.id} {...props} />;
    default:
      return <TextWorkspace key={question.id} {...props} />;
  }
}

/** Asks for the next question when the server says it is decided but not produced yet, once per server version. */
function useAutoNext(session: Session, commands: RoomCommands, active: boolean) {
  const asked = useRef<number | null>(null);
  const { next } = commands;
  useEffect(() => {
    if (active && asked.current !== session.version) {
      asked.current = session.version;
      next.mutate();
    }
  }, [active, session.version, next]);
}

function LoadedRoom({ session, receivedAt }: { session: Session; receivedAt: number }) {
  const location = useLocation();
  const commands = useRoomCommands(session.id);
  // Not the countdown itself: that would re-render the whole room, editors included, every second (RoomHeader shows it).
  const timeUp = useTimeUp(session, receivedAt);
  const phase = roomPhase(session);
  const [confirmEnd, setConfirmEnd] = useState(false);
  // A question that is already open when the page loads is restored, not "arriving": only later ones are revealed.
  const [openedWith] = useState(() => session.currentQuestion?.id ?? null);
  const { refetch } = useSession(session.id);

  useAutoNext(session, commands, phase.kind === 'preparing');

  // The clock reached zero: the server decides what that means (API_DESIGN.md section 1, Time).
  const running = phase.kind === 'question' || phase.kind === 'preparing';
  useEffect(() => {
    if (running && timeUp) {
      void refetch();
    }
  }, [running, timeUp, refetch]);

  usePageTitle(phase.kind === 'question' ? 'Interview in progress' : 'Interview');

  switch (phase.kind) {
    case 'completed':
      return <Navigate to={`/interviews/${session.id}/results`} replace />;
    case 'briefing':
      return <Briefing session={session} commands={commands} />;
    case 'abandoned':
      return (
        <div className="mx-auto w-full max-w-xl space-y-4 px-4 py-16 text-center">
          <h1 className="text-headline">This interview was abandoned</h1>
          <p className="text-ink-muted">It was closed before it started, so there is no report.</p>
          <ButtonLink to="/interviews/new">Start a new interview</ButtonLink>
        </div>
      );
    case 'wrapup':
      return <WrapUp interviewId={session.id} commands={commands} />;
    default:
      break;
  }

  // From here the interview is running. Each section has its own page; send the browser to the right one.
  if (phase.kind === 'question') {
    const wanted = workspacePath(session.id, phase.question.section);
    if (location.pathname !== wanted) {
      return <Navigate to={wanted} replace />;
    }
  }

  const question = phase.kind === 'question' ? phase.question : null;

  return (
    <>
      <RoomHeader
        session={session}
        receivedAt={receivedAt}
        endDisabled={
          commands.answer.isPending || commands.skip.isPending || commands.next.isPending
        }
        onEnd={() => {
          setConfirmEnd(true);
        }}
      />
      <div className="mx-auto grid w-full max-w-7xl flex-1 items-start gap-6 px-4 py-6 sm:px-6 lg:py-8 xl:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="min-w-0 space-y-5">
          {timeUp ? (
            <Alert tone="warning" title="Time is up">
              The interview is being closed. Your answers so far are kept.
            </Alert>
          ) : null}

          {question ? (
            <Workspace
              session={session}
              question={question}
              fresh={question.id !== openedWith}
              commands={commands}
            />
          ) : phase.kind === 'preparing' ? (
            <PreparingNext commands={commands} />
          ) : (
            <Alert tone="warning" title="Nothing to answer right now">
              <p>The interview is waiting for the server. Reload to continue.</p>
              <Button className="mt-3" size="sm" variant="secondary" onClick={() => void refetch()}>
                Reload
              </Button>
            </Alert>
          )}
        </div>
        <aside aria-label="Interview details">
          <div className="space-y-5 xl:sticky xl:top-24">
            <ProgressPanel session={session} />
          </div>
        </aside>
      </div>

      <Dialog
        open={confirmEnd}
        onClose={() => {
          setConfirmEnd(false);
        }}
        title="End the interview now?"
        description="Answers you have given are evaluated and you get a report. The questions you have not reached are not asked."
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setConfirmEnd(false);
              }}
            >
              Keep going
            </Button>
            <Button
              loading={commands.finish.isPending}
              onClick={() => {
                commands.finish.mutate('CANDIDATE_ENDED', {
                  onSuccess: () => {
                    setConfirmEnd(false);
                  },
                });
              }}
            >
              End and get my report
            </Button>
          </>
        }
      >
        {commands.finish.isError ? <ErrorState error={commands.finish.error} /> : null}
      </Dialog>
    </>
  );
}

function PreparingNext({ commands }: { commands: RoomCommands }) {
  const { next } = commands;
  if (next.isError) {
    const unavailable =
      next.error instanceof ApiError && next.error.code === 'AI_PROVIDER_UNAVAILABLE';
    return (
      <ErrorState
        error={next.error}
        title={unavailable ? 'The next question could not be prepared' : undefined}
        retrying={next.isPending}
        onRetry={() => {
          next.mutate();
        }}
      />
    );
  }
  return <ThinkingPanel title="Preparing your next question" />;
}

export function InterviewRoom({ interviewId }: { interviewId: string }) {
  const query = useSession(interviewId);

  if (query.isError && !query.data) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <div className="mx-auto w-full max-w-xl space-y-4 px-4 py-16">
        <ErrorState
          error={query.error}
          onRetry={() => void query.refetch()}
          retrying={query.isFetching}
        />
        {notFound ? (
          <Link to="/dashboard" className="text-brand font-semibold underline underline-offset-4">
            Back to your dashboard
          </Link>
        ) : null}
      </div>
    );
  }
  if (!query.data) {
    return <RoomSkeleton />;
  }
  return <LoadedRoom session={query.data} receivedAt={query.dataUpdatedAt} />;
}
