import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { report, summary } from '../../test/fixtures';
import { must } from '../../test/must';
import { API, problem } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

function page(items: ReturnType<typeof summary>[]) {
  return { items, page: 0, size: 50, totalItems: items.length, totalPages: 1 };
}

function backend(
  items: ReturnType<typeof summary>[],
  reports: Record<string, ReturnType<typeof report>> = {},
) {
  server.use(
    http.get(`${API}/interviews`, () => HttpResponse.json(page(items))),
    http.get(`${API}/interviews/:id/report`, ({ params }) => {
      const found = reports[params.id as string];
      return found ? HttpResponse.json(found) : problem(404, 'NOT_FOUND');
    }),
  );
}

describe('DashboardPage', () => {
  it('invites a new user to start a first interview', async () => {
    backend([]);
    renderRoute('/dashboard', { signedIn: true });

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Welcome back, Ada' }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Your first interview is a few clicks away'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Set up an interview' })).toHaveAttribute(
      'href',
      '/interviews/new',
    );
  });

  it('shows scores, the trend, performance by technology and recent interviews from the backend', async () => {
    const items = [
      summary({ id: 'c', endedAt: '2026-10-09T12:00:00Z' }),
      summary({ id: 'b', endedAt: '2026-10-05T12:00:00Z' }),
      summary({ id: 'a', endedAt: '2026-10-01T12:00:00Z' }),
    ];
    backend(items, {
      a: report({ interviewId: 'a', overallScore: 50 }),
      b: report({ interviewId: 'b', overallScore: 65 }),
      c: report({ interviewId: 'c', overallScore: 80, recommendation: 'STRONG' }),
    });
    renderRoute('/dashboard', { signedIn: true });

    const stats = must((await screen.findByText('Average score')).closest('dl'));
    expect(await within(stats).findByText('65')).toBeInTheDocument();
    expect(within(stats).getByText('3')).toBeInTheDocument();
    expect(within(stats).getByText('+15 vs the interview before')).toBeInTheDocument();
    expect(within(stats).getByText('+30 since your first')).toBeInTheDocument();

    const trend = await screen.findByRole('img', {
      name: /Overall score per interview: 3 values, from 50 to 80/,
    });
    expect(trend).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Overall score per interview' });
    expect(within(table).getAllByRole('row')).toHaveLength(4);

    const technology = screen.getByRole('region', { name: 'Performance by technology' });
    expect(within(technology).getByText('Java')).toBeInTheDocument();
    expect(within(technology).getByText('12 answers in 3 interviews')).toBeInTheDocument();

    expect(screen.getByRole('region', { name: 'Recent strengths' })).toHaveTextContent(
      'Clear explanation of the memory model',
    );
    expect(screen.getByRole('region', { name: 'Recent weaknesses' })).toHaveTextContent(
      'Self-invocation and Spring proxies',
    );
    const recurring = screen.getByRole('region', { name: 'Recurring weak topics' });
    expect(within(recurring).getByText('Spring / AOP')).toBeInTheDocument();
    expect(within(recurring).getByText('3 interviews')).toBeInTheDocument();

    const recent = screen.getByRole('region', { name: 'Recent interviews' });
    expect(within(recent).getAllByRole('link')).toHaveLength(4);
  });

  it('points to an interview that is still running', async () => {
    backend([summary({ id: 'run', state: 'QUESTIONING', endedAt: null })]);
    renderRoute('/dashboard', { signedIn: true });

    const banner = await screen.findByText('You have an interview in progress');
    expect(banner.closest('[role="status"]')).toHaveTextContent(/The clock is still running/);
    expect(screen.getAllByRole('link', { name: /continue/i })[0]).toHaveAttribute(
      'href',
      '/interviews/run/live',
    );
  });

  it('does not chart interviews without a score and does not count them as zero', async () => {
    backend([summary({ id: 'a' })], {
      a: report({
        interviewId: 'a',
        outcome: 'INCONCLUSIVE',
        overallScore: null,
        recommendation: null,
      }),
    });
    renderRoute('/dashboard', { signedIn: true });

    expect(await screen.findByText('Nothing to chart yet')).toBeInTheDocument();
    const stats = must(screen.getByText('Average score').closest('div'));
    expect(stats).toHaveTextContent('—');
  });

  it('says so when some reports could not be loaded', async () => {
    backend([summary({ id: 'a' }), summary({ id: 'b' })], { a: report({ interviewId: 'a' }) });
    renderRoute('/dashboard', { signedIn: true });

    expect(await screen.findByText('Some reports could not be loaded')).toBeInTheDocument();
  });

  it('shows an error with a retry when the interviews cannot be loaded', async () => {
    server.use(http.get(`${API}/interviews`, () => problem(500, 'INTERNAL_ERROR')));
    renderRoute('/dashboard', { signedIn: true });

    expect(await screen.findByText(/Your interviews could not be loaded/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
