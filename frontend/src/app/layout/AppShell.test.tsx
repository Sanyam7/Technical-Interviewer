import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { API } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

function dashboardBackend() {
  server.use(
    http.get(`${API}/interviews`, () =>
      HttpResponse.json({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 }),
    ),
  );
}

describe('AppShell', () => {
  it('offers the main pages and marks the current one', async () => {
    dashboardBackend();
    renderRoute('/dashboard', { signedIn: true });

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: 'New interview' })).toHaveAttribute(
      'href',
      '/interviews/new',
    );
    expect(within(nav).getByRole('link', { name: 'History' })).toHaveAttribute(
      'href',
      '/interviews',
    );
    expect(within(nav).getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/profile');
    expect(within(nav).getByRole('link', { name: 'History' })).not.toHaveAttribute('aria-current');
  });

  it('shows who is signed in and signs out', async () => {
    const user = userEvent.setup();
    dashboardBackend();
    server.use(http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })));
    renderRoute('/dashboard', { signedIn: true });

    expect(await screen.findByText('ada@example.com')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Welcome back' }),
    ).toBeInTheDocument();
  });

  it('removes the saved answer and diagram drafts when signing out, and nothing else', async () => {
    const user = userEvent.setup();
    dashboardBackend();
    server.use(http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })));
    localStorage.setItem('ti.draft.question-1', 'half an answer');
    localStorage.setItem('ti.diagram.interview-1', '{"components":[],"connections":[]}');
    localStorage.setItem('theme', 'dark');
    renderRoute('/dashboard', { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    await screen.findByRole('heading', { level: 1, name: 'Welcome back' });

    expect(localStorage.getItem('ti.draft.question-1')).toBeNull();
    expect(localStorage.getItem('ti.diagram.interview-1')).toBeNull();
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('moves focus to the page after navigating, so keyboard and screen-reader users start at the new content', async () => {
    const user = userEvent.setup();
    dashboardBackend();
    server.use(
      http.get(`${API}/interview-configurations`, () =>
        HttpResponse.json({ items: [], page: 0, size: 5, totalItems: 0, totalPages: 0 }),
      ),
    );
    renderRoute('/dashboard', { signedIn: true });

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    await user.click(within(nav).getByRole('link', { name: 'New interview' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Set up your interview' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveFocus();
  });
});
