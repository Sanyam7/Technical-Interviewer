import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { report, summary } from '../../test/fixtures';
import { must } from '../../test/must';
import { API } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

describe('HistoryPage', () => {
  it('lists interviews with their state and score, and links each to where it leads', async () => {
    server.use(
      http.get(`${API}/interviews`, () =>
        HttpResponse.json({
          items: [
            summary({ id: 'done' }),
            summary({ id: 'live', state: 'CODING', endedAt: null }),
            summary({ id: 'gone', state: 'ABANDONED', endedAt: null }),
          ],
          page: 0,
          size: 10,
          totalItems: 3,
          totalPages: 1,
        }),
      ),
      http.get(`${API}/interviews/done/report`, () =>
        HttpResponse.json(report({ overallScore: 72.5 })),
      ),
    );
    renderRoute('/interviews', { signedIn: true });

    const list = await screen.findByRole('list', { name: 'Interviews' });
    const rows = within(list).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(await within(must(rows[0])).findByText('72.5')).toBeInTheDocument();
    expect(within(must(rows[0])).getByRole('link')).toHaveAttribute(
      'href',
      '/interviews/done/results',
    );
    expect(within(must(rows[1])).getByRole('link')).toHaveAttribute(
      'href',
      '/interviews/live/live',
    );
    expect(within(must(rows[1])).getByText('Coding')).toBeInTheDocument();
    expect(within(must(rows[2])).queryByRole('link')).not.toBeInTheDocument();
  });

  it('filters by state through the backend and pages through the results', async () => {
    const user = userEvent.setup();
    const requested: string[] = [];
    server.use(
      http.get(`${API}/interviews`, ({ request }) => {
        const url = new URL(request.url);
        requested.push(
          `${url.searchParams.get('state') ?? 'all'}:${url.searchParams.get('page') ?? '0'}`,
        );
        return HttpResponse.json({
          items: [summary({ id: `i-${url.searchParams.get('page') ?? '0'}`, state: 'ABANDONED' })],
          page: Number(url.searchParams.get('page') ?? 0),
          size: 10,
          totalItems: 25,
          totalPages: 3,
        });
      }),
    );
    renderRoute('/interviews', { signedIn: true });

    await screen.findByRole('list', { name: 'Interviews' });
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Show'), 'ABANDONED');
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument();
    expect(requested).toEqual(['all:0', 'all:1', 'ABANDONED:0']);
  });

  it('shows an empty state, with a way out of an empty filter', async () => {
    server.use(
      http.get(`${API}/interviews`, () =>
        HttpResponse.json({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
      ),
    );
    renderRoute('/interviews', { signedIn: true });

    expect(await screen.findByText('No interviews yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start your first interview' })).toBeInTheDocument();
  });

  it('offers the first page instead of "no interviews yet" on a page past the end', async () => {
    const user = userEvent.setup();
    const pages: string[] = [];
    server.use(
      http.get(`${API}/interviews`, ({ request }) => {
        const page = new URL(request.url).searchParams.get('page') ?? '0';
        pages.push(page);
        return HttpResponse.json(
          page === '0'
            ? { items: [summary({ id: 'done' })], page: 0, size: 10, totalItems: 1, totalPages: 1 }
            : { items: [], page: Number(page), size: 10, totalItems: 1, totalPages: 1 },
        );
      }),
      http.get(`${API}/interviews/done/report`, () => HttpResponse.json(report())),
    );
    renderRoute('/interviews?page=4', { signedIn: true });

    expect(await screen.findByText('This page is empty')).toBeInTheDocument();
    expect(screen.queryByText('No interviews yet')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Go to the first page' }));
    expect(await screen.findByRole('list', { name: 'Interviews' })).toBeInTheDocument();
    expect(pages).toContain('0');
  });
});
