import { http, HttpResponse } from 'msw';

import type { HealthResponse } from '../api/health';
import { OPTIONS, SKILLS, TOKEN } from './fixtures';

export const API_ORIGIN = 'http://api.test';
export const API = `${API_ORIGIN}/api/v1`;
export const HEALTH_URL = `${API}/health`;

export const healthUp: HealthResponse = {
  status: 'UP',
  service: 'tech-interviewer',
  version: '0.1.0',
  serverTime: '2026-10-05T12:30:45Z',
  database: 'UP',
};

export const healthDown: HealthResponse = { ...healthUp, status: 'DOWN', database: 'DOWN' };

export const healthHandlers = {
  up: http.get(HEALTH_URL, () => HttpResponse.json(healthUp)),
  down: http.get(HEALTH_URL, () => HttpResponse.json(healthDown, { status: 503 })),
  problem: http.get(HEALTH_URL, () =>
    HttpResponse.json(
      {
        type: 'https://techinterviewer.dev/problems/internal-error',
        title: 'Internal Server Error',
        status: 503,
        code: 'INTERNAL_ERROR',
        traceId: 'trace-12345678',
      },
      { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  ),
  networkError: http.get(HEALTH_URL, () => HttpResponse.error()),
};

/** A problem document as the backend sends it (API_DESIGN.md section 8). */
export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    {
      type: `https://techinterviewer.dev/problems/${code.toLowerCase()}`,
      title: code,
      status,
      detail: `${code} detail`,
      instance: '/api/v1/x',
      code,
      traceId: 'trace-abcdef12',
      ...extra,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

export const authHandlers = {
  /** No refresh cookie: the visitor is anonymous. */
  anonymous: http.post(`${API}/auth/refresh`, () => problem(401, 'UNAUTHENTICATED')),
  /** A valid refresh cookie: the page load signs in. */
  signedIn: http.post(`${API}/auth/refresh`, () => HttpResponse.json(TOKEN)),
};

export const catalogHandlers = [
  http.get(`${API}/catalog/skills`, () => HttpResponse.json(SKILLS)),
  http.get(`${API}/catalog/options`, () => HttpResponse.json(OPTIONS)),
];

/** No résumé uploaded (the default of every test). */
export const noResume = http.get(`${API}/profile/resume`, () => problem(404, 'NOT_FOUND'));

export const handlers = [healthHandlers.up, authHandlers.anonymous, ...catalogHandlers, noResume];
