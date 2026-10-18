import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { API, problem } from '../test/handlers';
import { TOKEN } from '../test/fixtures';
import { server } from '../test/server';
import { login, logout } from './auth';
import { ApiError } from './http';
import { authFetch, onSessionLost, refreshSession, setAccessToken } from './session';

describe('authFetch', () => {
  it('sends the access token as a bearer token', async () => {
    setAccessToken('abc');
    let header: string | null = null;
    server.use(
      http.get(`${API}/users/me`, ({ request }) => {
        header = request.headers.get('Authorization');
        return HttpResponse.json({ ok: true });
      }),
    );

    await authFetch('/api/v1/users/me');

    expect(header).toBe('Bearer abc');
  });

  it('renews an expired token once and repeats the request with the new one', async () => {
    setAccessToken('old');
    const seen: (string | null)[] = [];
    server.use(
      http.get(`${API}/profile`, ({ request }) => {
        const auth = request.headers.get('Authorization');
        seen.push(auth);
        return auth === 'Bearer old'
          ? problem(401, 'TOKEN_EXPIRED')
          : HttpResponse.json({ headline: 'Hi' });
      }),
      http.post(`${API}/auth/refresh`, ({ request }) => {
        expect(request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
        return HttpResponse.json({ ...TOKEN, accessToken: 'new' });
      }),
    );

    await expect(authFetch('/api/v1/profile')).resolves.toEqual({ headline: 'Hi' });
    expect(seen).toEqual(['Bearer old', 'Bearer new']);
  });

  it('shares one refresh between concurrent requests, because a refresh token works only once', async () => {
    setAccessToken('old');
    let refreshes = 0;
    server.use(
      http.get(`${API}/profile`, ({ request }) =>
        request.headers.get('Authorization') === 'Bearer old'
          ? problem(401, 'TOKEN_EXPIRED')
          : HttpResponse.json({ ok: true }),
      ),
      http.post(`${API}/auth/refresh`, async () => {
        refreshes += 1;
        await new Promise((resolve) => setTimeout(resolve, 20));
        return HttpResponse.json({ ...TOKEN, accessToken: 'new' });
      }),
    );

    await Promise.all([
      authFetch('/api/v1/profile'),
      authFetch('/api/v1/profile'),
      authFetch('/api/v1/profile'),
    ]);

    expect(refreshes).toBe(1);
  });

  it('tells the app the session is lost when the refresh is refused', async () => {
    setAccessToken('old');
    const lost = vi.fn();
    onSessionLost(lost);
    server.use(
      http.get(`${API}/profile`, () => problem(401, 'TOKEN_EXPIRED')),
      http.post(`${API}/auth/refresh`, () => problem(401, 'UNAUTHENTICATED')),
    );

    await expect(authFetch('/api/v1/profile')).rejects.toMatchObject({ code: 'TOKEN_EXPIRED' });
    expect(lost).toHaveBeenCalledTimes(1);
  });

  it('treats any other 401 as a lost session without trying to refresh', async () => {
    setAccessToken('bad');
    const lost = vi.fn();
    onSessionLost(lost);
    let refreshes = 0;
    server.use(
      http.get(`${API}/profile`, () => problem(401, 'UNAUTHENTICATED')),
      http.post(`${API}/auth/refresh`, () => {
        refreshes += 1;
        return HttpResponse.json(TOKEN);
      }),
    );

    await expect(authFetch('/api/v1/profile')).rejects.toBeInstanceOf(ApiError);
    expect(refreshes).toBe(0);
    expect(lost).toHaveBeenCalledTimes(1);
  });

  it('passes other errors through untouched', async () => {
    setAccessToken('abc');
    server.use(http.get(`${API}/profile`, () => problem(404, 'NOT_FOUND')));

    await expect(authFetch('/api/v1/profile')).rejects.toMatchObject({ status: 404 });
  });
});

describe('refreshSession', () => {
  it('resolves to null when there is no session, and rejects when the backend is unreachable', async () => {
    await expect(refreshSession()).resolves.toBeNull();

    server.use(http.post(`${API}/auth/refresh`, () => HttpResponse.error()));
    await expect(refreshSession()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });
});

describe('login and logout', () => {
  it('keeps the token from a login in memory and drops it on logout', async () => {
    server.use(
      http.post(`${API}/auth/login`, async ({ request }) => {
        expect(await request.json()).toEqual({
          email: 'ada@example.com',
          password: 'secret-password',
        });
        return HttpResponse.json({ ...TOKEN, accessToken: 'fresh' });
      }),
      http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
      http.get(`${API}/users/me`, ({ request }) =>
        HttpResponse.json({ auth: request.headers.get('Authorization') }),
      ),
    );

    await login({ email: 'ada@example.com', password: 'secret-password' });
    await expect(authFetch('/api/v1/users/me')).resolves.toEqual({ auth: 'Bearer fresh' });

    await logout();
    await expect(authFetch('/api/v1/users/me')).resolves.toEqual({ auth: null });
  });

  it('drops the token even when the logout request fails', async () => {
    setAccessToken('abc');
    server.use(
      http.post(`${API}/auth/logout`, () => HttpResponse.error()),
      http.get(`${API}/users/me`, ({ request }) =>
        HttpResponse.json({ auth: request.headers.get('Authorization') }),
      ),
    );

    await expect(logout()).rejects.toBeInstanceOf(ApiError);
    await expect(authFetch('/api/v1/users/me')).resolves.toEqual({ auth: null });
  });
});
