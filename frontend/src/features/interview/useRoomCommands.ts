import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '../../api/http';
import {
  finishInterview,
  interviewKeys,
  requestNextQuestion,
  startInterview,
  submitAnswer,
  skipQuestion,
} from '../../api/interviews';
import { clearStoredDraft } from './useDraft';
import type { AnswerResult, Architecture, FinishReason, Question, Session } from '../../api/types';

export interface AnswerInput {
  question: Question;
  content: string;
  codeLanguage: string | null;
  timeTakenSeconds: number;
  /** The architecture diagram of a system design answer; absent when none was drawn. */
  architecture?: Architecture | undefined;
}

/** What an answer or a skip changed, in the shape of a session. */
function applyTurn(session: Session, result: AnswerResult): Session {
  return {
    ...session,
    state: result.state,
    progress: result.progress,
    currentQuestion: result.nextQuestion ?? null,
    awaitingNextQuestion: result.awaitingNextQuestion,
    interviewComplete: result.interviewComplete,
  };
}

/** The mutations of the interview room. The cache always holds what the server last said; a conflict reloads it. */
export function useRoomCommands(interviewId: string) {
  const queryClient = useQueryClient();
  const sessionKey = interviewKeys.session(interviewId);

  const setSession = (session: Session) => {
    queryClient.setQueryData(sessionKey, session);
  };

  const afterTurn = (result: AnswerResult) => {
    // The merged session keeps the server time of the response it came from, so it keeps that response's timestamp too: the
    // countdown measures the clock offset from the pair (useCountdown).
    queryClient.setQueryData<Session>(
      sessionKey,
      (previous) => (previous ? applyTurn(previous, result) : previous),
      { updatedAt: queryClient.getQueryState(sessionKey)?.dataUpdatedAt },
    );
    // The session also carries the clock and the version: take them from the server too.
    void queryClient.invalidateQueries({ queryKey: sessionKey });
    void queryClient.invalidateQueries({ queryKey: interviewKeys.transcript(interviewId) });
  };

  /**
   * A 409 means the interview moved on (another tab, the clock); a timeout or a lost connection (status 0) means the server may
   * have done the work without the reply arriving. Either way, show what the server says now: if the turn went through, the
   * room moves on instead of offering to send it again.
   */
  const resync = (error: unknown) => {
    if (error instanceof ApiError && (error.status === 409 || error.status === 0)) {
      void queryClient.invalidateQueries({ queryKey: sessionKey });
    }
  };

  const start = useMutation({
    mutationFn: () => startInterview(interviewId),
    onSuccess: setSession,
    onError: resync,
  });

  const answer = useMutation({
    mutationFn: (input: AnswerInput) =>
      submitAnswer(interviewId, {
        questionId: input.question.id,
        content: input.content,
        codeLanguage: input.codeLanguage,
        timeTakenSeconds: input.timeTakenSeconds,
        ...(input.architecture ? { architecture: input.architecture } : {}),
      }),
    onSuccess: (result, input) => {
      clearStoredDraft(input.question.id);
      afterTurn(result);
    },
    onError: resync,
  });

  const skip = useMutation({
    mutationFn: (input: { question: Question; timeTakenSeconds: number }) =>
      skipQuestion(interviewId, input.question.id, input.timeTakenSeconds),
    onSuccess: (result, input) => {
      clearStoredDraft(input.question.id);
      afterTurn(result);
    },
    onError: resync,
  });

  const next = useMutation({
    mutationFn: () => requestNextQuestion(interviewId),
    onSuccess: (session) => {
      setSession(session);
      void queryClient.invalidateQueries({ queryKey: interviewKeys.transcript(interviewId) });
    },
    onError: resync,
  });

  const finish = useMutation({
    mutationFn: (reason: FinishReason) => finishInterview(interviewId, reason),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: sessionKey });
      void queryClient.invalidateQueries({ queryKey: interviewKeys.report(interviewId) });
      void queryClient.invalidateQueries({ queryKey: interviewKeys.all });
    },
    onError: resync,
  });

  return { start, answer, skip, next, finish };
}

export type RoomCommands = ReturnType<typeof useRoomCommands>;
