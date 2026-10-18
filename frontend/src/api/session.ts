import { ApiError, apiFetch, type ApiRequestInit } from './http';
import type { TokenResponse } from './types';

/**
 * The access token lives in this module's memory and nowhere else (not in storage, not in a cookie): a page reload
 * signs in again through the HttpOnly refresh cookie (API_DESIGN.md section 2).
 */
let accessToken: string | null = null;
let refreshInFlight: Promise<TokenResponse | null> | null = null;
let sessionLostHandler: (() => void) | null = null;

const REFRESH_LOCK = 'tech-interviewer.refresh';

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function hasAccessToken(): boolean {
  return accessToken !== null;
}

/** The auth provider registers what happens when the session cannot be renewed any more. */
export function onSessionLost(handler: (() => void) | null): void {
  sessionLostHandler = handler;
}

/** For tests: forget the token and any refresh in progress. */
export function resetSession(): void {
  accessToken = null;
  refreshInFlight = null;
  sessionLostHandler = null;
}

/** Credentials are sent explicitly so the refresh cookie also works when the API is on another origin. */
export const COOKIE_REQUEST: ApiRequestInit = { credentials: 'include' };

const REFRESH_HEADERS = { 'X-Requested-With': 'XMLHttpRequest' };

async function requestRefresh(): Promise<TokenResponse | null> {
  try {
    const token = await apiFetch<TokenResponse>('/api/v1/auth/refresh', {
      method: 'POST',
      headers: REFRESH_HEADERS,
      ...COOKIE_REQUEST,
    });
    accessToken = token.accessToken;
    return token;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      accessToken = null;
      return null;
    }
    throw error;
  }
}

/** Runs the refresh while holding a lock shared by all tabs, where the browser has one (a rotated token is single use). */
function refreshOnce(): Promise<TokenResponse | null> {
  if (typeof navigator !== 'undefined' && 'locks' in navigator) {
    return navigator.locks.request(REFRESH_LOCK, requestRefresh);
  }
  return requestRefresh();
}

/**
 * Renews the access token from the refresh cookie. Concurrent callers share one request: the refresh token is rotated on
 * every use and a second use of the same token ends the session (API_DESIGN.md section 2). Resolves to null when there is
 * no valid session (not signed in), rejects when the backend cannot be reached.
 */
export function refreshSession(): Promise<TokenResponse | null> {
  refreshInFlight ??= refreshOnce().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

function withToken(init: ApiRequestInit): ApiRequestInit {
  const headers = new Headers(init.headers);
  if (accessToken !== null) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  return { ...init, headers };
}

/**
 * Calls an endpoint that needs a signed-in user. An expired access token is renewed once and the request repeated; when
 * the session cannot be renewed the provider is told and the original error is thrown.
 */
export async function authFetch<T>(path: string, init: ApiRequestInit = {}): Promise<T> {
  try {
    return await apiFetch<T>(path, withToken(init));
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) {
      throw error;
    }
    if (error.code === 'TOKEN_EXPIRED') {
      const renewed = await refreshSession();
      if (renewed !== null) {
        return apiFetch<T>(path, withToken(init));
      }
    }
    accessToken = null;
    sessionLostHandler?.();
    throw error;
  }
}

export function jsonBody(
  value: unknown,
  method: 'POST' | 'PUT' = 'POST',
): Pick<ApiRequestInit, 'body' | 'method'> {
  return { method, body: JSON.stringify(value) };
}
