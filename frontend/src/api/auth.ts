import { apiFetch } from './http';
import { COOKIE_REQUEST, jsonBody, setAccessToken } from './session';
import type { LoginRequest, RegisterRequest, TokenResponse } from './types';

export async function login(request: LoginRequest): Promise<TokenResponse> {
  const token = await apiFetch<TokenResponse>('/api/v1/auth/login', {
    ...jsonBody(request),
    ...COOKIE_REQUEST,
  });
  setAccessToken(token.accessToken);
  return token;
}

export async function register(request: RegisterRequest): Promise<TokenResponse> {
  const token = await apiFetch<TokenResponse>('/api/v1/auth/register', {
    ...jsonBody(request),
    ...COOKIE_REQUEST,
  });
  setAccessToken(token.accessToken);
  return token;
}

/** Revokes the session on the server (idempotent); the token is dropped whatever the outcome. */
export async function logout(): Promise<void> {
  try {
    await apiFetch<undefined>('/api/v1/auth/logout', {
      method: 'POST',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      ...COOKIE_REQUEST,
    });
  } finally {
    setAccessToken(null);
  }
}
