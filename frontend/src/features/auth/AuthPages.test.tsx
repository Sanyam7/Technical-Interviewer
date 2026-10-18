import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { TOKEN } from '../../test/fixtures';
import { API, problem } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

function dashboardBackend() {
  server.use(
    http.get(`${API}/interviews`, () =>
      HttpResponse.json({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 }),
    ),
  );
}

describe('LoginPage', () => {
  it('signs in, sends the credentials once and lands on the dashboard', async () => {
    const user = userEvent.setup();
    dashboardBackend();
    let body: unknown;
    server.use(
      http.post(`${API}/auth/login`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(TOKEN);
      }),
    );
    renderRoute('/login');

    await user.type(await screen.findByLabelText('Email'), ' ada@example.com ');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: /welcome back, ada/i }),
    ).toBeInTheDocument();
    expect(body).toEqual({ email: 'ada@example.com', password: 'correct-horse-battery' });
  });

  it('says the credentials are wrong without saying which part', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/auth/login`, () => problem(401, 'INVALID_CREDENTIALS')));
    renderRoute('/login');

    await user.type(await screen.findByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The email or the password is not right.',
    );
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('reports an unreachable backend', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/auth/login`, () => HttpResponse.error()));
    renderRoute('/login');

    await user.type(await screen.findByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'whatever-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server');
  });

  it('returns to the page the visitor was trying to reach', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN)),
      http.get(`${API}/profile`, () =>
        HttpResponse.json({
          headline: null,
          yearsExperience: null,
          jobTitle: null,
          skills: [],
          updatedAt: '2026-10-08T12:00:00Z',
        }),
      ),
    );
    renderRoute('/profile');

    await user.type(await screen.findByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Ada Lovelace' }),
    ).toBeInTheDocument();
  });

  it('returns to the exact address, with its query, after signing in', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN)),
      http.get(`${API}/interviews`, () =>
        HttpResponse.json({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
      ),
    );
    const { router } = renderRoute('/interviews?state=COMPLETED');

    await user.type(await screen.findByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('heading', { level: 1, name: 'Your interviews' });
    expect(router.state.location.pathname).toBe('/interviews');
    expect(router.state.location.search).toBe('?state=COMPLETED');
  });
});

describe('RegisterPage', () => {
  it('creates the account and signs in', async () => {
    const user = userEvent.setup();
    dashboardBackend();
    let body: unknown;
    server.use(
      http.post(`${API}/auth/register`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(TOKEN, { status: 201 });
      }),
    );
    renderRoute('/register');

    await user.type(await screen.findByLabelText('Name'), 'Ada Lovelace');
    await user.type(screen.getByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: /welcome back, ada/i }),
    ).toBeInTheDocument();
    expect(body).toEqual({
      displayName: 'Ada Lovelace',
      email: 'ada@example.com',
      password: 'correct-horse-battery',
    });
  });

  it('shows a taken email next to the email field', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/auth/register`, () => problem(409, 'EMAIL_TAKEN')));
    renderRoute('/register');

    await user.type(await screen.findByLabelText('Name'), 'Ada');
    await user.type(screen.getByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText(/already registered/)).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows field errors from the backend', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API}/auth/register`, () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'password', message: 'must be 10 to 72 bytes' }],
        }),
      ),
    );
    renderRoute('/register');

    await user.type(await screen.findByLabelText('Name'), 'Ada');
    await user.type(screen.getByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'long-enough-1');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('must be 10 to 72 bytes')).toBeInTheDocument();
  });
});
