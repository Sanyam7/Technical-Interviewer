import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { API, healthHandlers } from '../test/handlers';
import { renderRoute } from '../test/renderWithProviders';
import { server } from '../test/server';

describe('public pages and layout', () => {
  it('renders the landing page with the live technologies from the catalog', async () => {
    renderRoute('/');

    const main = await screen.findByRole('main');
    expect(
      await within(main).findByRole('heading', {
        level: 1,
        name: /interview practice that shows its work/i,
      }),
    ).toBeInTheDocument();
    expect(await within(main).findByText('Spring Boot')).toBeInTheDocument();
    expect(within(main).getByRole('link', { name: 'Start practising' })).toHaveAttribute(
      'href',
      '/register',
    );
  });

  it('hides the technologies section when the catalog cannot be loaded', async () => {
    server.use(http.get(`${API}/catalog/skills`, () => HttpResponse.error()));
    renderRoute('/');

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByText('Interview on the stack you use')).not.toBeInTheDocument();
  });

  it('exposes banner, main and contentinfo landmarks and a skip link', async () => {
    renderRoute('/');

    const banner = await screen.findByRole('banner');
    expect(within(banner).getByRole('link', { name: 'AI Technical Interviewer' })).toHaveAttribute(
      'href',
      '/',
    );
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute(
      'href',
      '#main-content',
    );
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
  });

  it('makes the skip link the first tab stop and hides it until it is focused', async () => {
    const user = userEvent.setup();
    renderRoute('/');
    const skipLink = await screen.findByRole('link', { name: 'Skip to main content' });
    expect(skipLink).toHaveClass('sr-only', 'focus:not-sr-only');

    await user.tab();

    expect(skipLink).toHaveFocus();
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
  });

  it('shows Sign in and Get started to a visitor and Dashboard to a signed-in user', async () => {
    const visitor = renderRoute('/');
    const nav = within(await screen.findByRole('navigation', { name: 'Account' }));
    expect(await nav.findByRole('link', { name: 'Get started' })).toBeInTheDocument();
    visitor.unmount();

    renderRoute('/', { signedIn: true });
    expect(
      await within(await screen.findByRole('navigation', { name: 'Account' })).findByRole('link', {
        name: 'Dashboard',
      }),
    ).toBeInTheDocument();
  });

  it('shows the system status page with the backend health', async () => {
    renderRoute('/status');

    expect(await screen.findByText('Operational')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Service status' })).toBeInTheDocument();
  });

  it('reports an unreachable backend on the status page', async () => {
    server.use(healthHandlers.networkError);
    renderRoute('/status');

    expect(await screen.findByText('Unreachable')).toBeInTheDocument();
  });

  it('renders NotFound for an unknown URL, still inside the layout, with a working link home', async () => {
    const user = userEvent.setup();
    renderRoute('/does/not/exist');

    const main = await screen.findByRole('main');
    expect(
      await within(main).findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('banner')).toBeInTheDocument();

    await user.click(within(main).getByRole('link', { name: 'Back to home' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: /interview practice/i }),
    ).toBeInTheDocument();
  });
});

describe('route guards', () => {
  it('sends an anonymous visitor from a private page to sign in', async () => {
    renderRoute('/dashboard');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Welcome back' }),
    ).toBeInTheDocument();
  });

  it('keeps a signed-in user away from the sign-in page', async () => {
    server.use(
      http.get(`${API}/interviews`, () =>
        HttpResponse.json({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 }),
      ),
    );
    renderRoute('/login', { signedIn: true });

    expect(
      await screen.findByRole('heading', { level: 1, name: /welcome back, ada/i }),
    ).toBeInTheDocument();
  });

  it('asks to try again when the backend cannot be reached while looking for a session', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/auth/refresh`, () => HttpResponse.error()));
    renderRoute('/dashboard');

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server');

    server.use(
      http.post(`${API}/auth/refresh`, () =>
        HttpResponse.json({
          accessToken: 'x',
          tokenType: 'Bearer',
          expiresIn: 900,
          user: { id: '1', email: 'a@b.c', displayName: 'Ada', role: 'CANDIDATE' },
        }),
      ),
    );
    server.use(
      http.get(`${API}/interviews`, () =>
        HttpResponse.json({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 }),
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: /welcome back, ada/i }),
    ).toBeInTheDocument();
  });
});
