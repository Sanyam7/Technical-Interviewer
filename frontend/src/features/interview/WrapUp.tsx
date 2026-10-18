import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';

import { ApiError } from '../../api/http';
import { sessionQuery } from '../../api/interviews';
import { ErrorState } from '../../components/ui/ErrorState';
import { ThinkingPanel } from './ThinkingPanel';
import type { RoomCommands } from './useRoomCommands';

/** How often the session is re-read while the report is written. */
export const WRAP_UP_POLL_MS = 5_000;

/**
 * The question phase is over. Finishing evaluates what is still pending and builds the report, which can take a while; when
 * it is done the report page takes over. Runs once on arrival, and again only on request. Meanwhile the session is re-read:
 * the server finishes the report even when the reply to this request never arrives (a slow model can outlast any client
 * timeout), and the room moves to the results as soon as the session says the interview is complete.
 */
export function WrapUp({ interviewId, commands }: { interviewId: string; commands: RoomCommands }) {
  const navigate = useNavigate();
  const started = useRef(false);
  const { finish } = commands;
  useQuery({ ...sessionQuery(interviewId), refetchInterval: WRAP_UP_POLL_MS });
  // A timeout or a lost connection says nothing about the report: the server is most likely still writing it.
  const lost = finish.error instanceof ApiError && finish.error.status === 0;

  useEffect(() => {
    if (!started.current) {
      started.current = true;
      finish.mutate('ACKNOWLEDGE_COMPLETE');
    }
  }, [finish]);

  useEffect(() => {
    if (finish.isSuccess) {
      void navigate(`/interviews/${interviewId}/results`, { replace: true });
    }
  }, [finish.isSuccess, interviewId, navigate]);

  return (
    <div className="mx-auto w-full max-w-2xl space-y-6 px-4 py-16 sm:px-6">
      <div>
        <p className="eyebrow">Interview complete</p>
        <h1 className="text-headline mt-2">Writing your report</h1>
        <p className="text-ink-muted mt-2">
          Every answer is being evaluated and the scores are computed. You will be taken to your
          results as soon as they are ready.
        </p>
      </div>
      {finish.isError && !lost ? (
        <ErrorState
          error={finish.error}
          title="Your report could not be finished"
          retrying={finish.isPending}
          onRetry={() => {
            finish.mutate('ACKNOWLEDGE_COMPLETE');
          }}
        />
      ) : (
        <ThinkingPanel title="Evaluating your interview">
          {lost
            ? 'This is taking longer than usual. Your report is still being written, and this page moves on as soon as it is ready.'
            : 'Answers that were still waiting for evaluation are rated now. This can take a minute or two.'}
        </ThinkingPanel>
      )}
    </div>
  );
}
