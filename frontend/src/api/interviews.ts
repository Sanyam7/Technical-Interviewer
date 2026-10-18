import { keepPreviousData, queryOptions, useQuery } from '@tanstack/react-query';

import { AI_REQUEST_TIMEOUT_MS } from './http';
import { authFetch, jsonBody } from './session';
import type {
  AnswerRequest,
  AnswerResult,
  CodeRunRequest,
  CodeRunResult,
  ConfigurationPage,
  CreateInterviewRequest,
  CreatedInterview,
  FinishReason,
  FinishResult,
  InterviewPage,
  InterviewState,
  Report,
  Session,
  Transcript,
} from './types';

const BASE = '/api/v1/interviews';

export interface InterviewListParams {
  page: number;
  size: number;
  state?: InterviewState;
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) {
      search.set(key, String(value));
    }
  }
  const text = search.toString();
  return text === '' ? '' : `?${text}`;
}

/* ------------------------------------------------------------------------------------------ queries */

export const interviewKeys = {
  all: ['interviews'] as const,
  list: (params: InterviewListParams) => ['interviews', 'list', params] as const,
  session: (id: string) => ['interviews', id, 'session'] as const,
  transcript: (id: string) => ['interviews', id, 'transcript'] as const,
  report: (id: string) => ['interviews', id, 'report'] as const,
  configurations: ['configurations'] as const,
};

export const interviewListQuery = (params: InterviewListParams) =>
  queryOptions({
    queryKey: interviewKeys.list(params),
    queryFn: ({ signal }) => authFetch<InterviewPage>(`${BASE}${query({ ...params })}`, { signal }),
  });

export const sessionQuery = (id: string) =>
  queryOptions({
    queryKey: interviewKeys.session(id),
    queryFn: ({ signal }) => authFetch<Session>(`${BASE}/${id}`, { signal }),
    // The server owns the state and the clock: a session is only as fresh as the last response.
    staleTime: 0,
  });

export const transcriptQuery = (id: string) =>
  queryOptions({
    queryKey: interviewKeys.transcript(id),
    queryFn: ({ signal }) => authFetch<Transcript>(`${BASE}/${id}/transcript`, { signal }),
  });

export const REPORT_POLL_MS = 2_000;

/**
 * The report answers 202 while it is made and 200 once it is READY or FAILED; both carry a body, so the status field is
 * what decides. A READY report never changes, so it is cached for good; a pending one is polled.
 */
export const reportQuery = (id: string) =>
  queryOptions({
    queryKey: interviewKeys.report(id),
    queryFn: ({ signal }) => authFetch<Report>(`${BASE}/${id}/report`, { signal }),
    staleTime: (q) => (q.state.data?.status === 'READY' ? Infinity : 0),
    refetchInterval: (q) => {
      const status = q.state.data?.status;
      return status === 'PENDING' || status === 'GENERATING' ? REPORT_POLL_MS : false;
    },
  });

export function useInterviewList(params: InterviewListParams) {
  return useQuery({ ...interviewListQuery(params), placeholderData: keepPreviousData });
}

export function useSession(id: string) {
  return useQuery(sessionQuery(id));
}

export function useTranscript(id: string, enabled = true) {
  return useQuery({ ...transcriptQuery(id), enabled });
}

export function useReport(id: string, enabled = true) {
  return useQuery({ ...reportQuery(id), enabled });
}

export const configurationsQuery = queryOptions({
  queryKey: interviewKeys.configurations,
  queryFn: ({ signal }) =>
    authFetch<ConfigurationPage>('/api/v1/interview-configurations?page=0&size=5', { signal }),
});

export function useRecentConfigurations() {
  return useQuery(configurationsQuery);
}

/* ---------------------------------------------------------------------------------------- commands */

export function createInterview(request: CreateInterviewRequest): Promise<CreatedInterview> {
  return authFetch<CreatedInterview>(BASE, jsonBody(request));
}

export function startInterview(id: string): Promise<Session> {
  return authFetch<Session>(`${BASE}/${id}/start`, { method: 'POST' });
}

export function abandonInterview(id: string): Promise<unknown> {
  return authFetch<unknown>(`${BASE}/${id}/abandon`, { method: 'POST' });
}

export function submitAnswer(id: string, request: AnswerRequest): Promise<AnswerResult> {
  return authFetch<AnswerResult>(`${BASE}/${id}/answers`, {
    ...jsonBody(request),
    timeoutMs: AI_REQUEST_TIMEOUT_MS,
  });
}

export function skipQuestion(
  id: string,
  questionId: string,
  timeTakenSeconds: number,
): Promise<AnswerResult> {
  return authFetch<AnswerResult>(`${BASE}/${id}/questions/${questionId}/skip`, {
    ...jsonBody({ timeTakenSeconds }),
    timeoutMs: AI_REQUEST_TIMEOUT_MS,
  });
}

export function requestNextQuestion(id: string): Promise<Session> {
  return authFetch<Session>(`${BASE}/${id}/questions/next`, {
    method: 'POST',
    timeoutMs: AI_REQUEST_TIMEOUT_MS,
  });
}

export function finishInterview(id: string, reason: FinishReason): Promise<FinishResult> {
  return authFetch<FinishResult>(`${BASE}/${id}/finish`, {
    ...jsonBody({ reason }),
    timeoutMs: AI_REQUEST_TIMEOUT_MS,
  });
}

/**
 * A run starts a sandbox container (and may wait for a free one): seconds normally, bounded by the backend's queue and run
 * limits (15 s + 30 s).
 */
export const CODE_RUN_TIMEOUT_MS = 60_000;

/** Runs a draft against the visible examples of the open coding question; nothing is stored or evaluated. */
export function runExamples(
  id: string,
  questionId: string,
  request: CodeRunRequest,
): Promise<CodeRunResult> {
  return authFetch<CodeRunResult>(`${BASE}/${id}/questions/${questionId}/runs`, {
    ...jsonBody(request),
    timeoutMs: CODE_RUN_TIMEOUT_MS,
  });
}

export function retryReport(id: string): Promise<Report> {
  return authFetch<Report>(`${BASE}/${id}/report/retry`, {
    method: 'POST',
    timeoutMs: AI_REQUEST_TIMEOUT_MS,
  });
}
