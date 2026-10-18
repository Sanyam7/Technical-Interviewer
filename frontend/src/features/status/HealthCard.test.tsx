import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createQueryClient } from '../../app/queryClient';
import { HEALTH_URL, healthHandlers, healthUp } from '../../test/handlers';
import { renderWithProviders } from '../../test/renderWithProviders';
import { server } from '../../test/server';
import { HealthCard } from './HealthCard';

describe('HealthCard', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a loading state while the first check is in flight', async () => {
    server.use(
      http.get(HEALTH_URL, async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        return HttpResponse.json(healthUp);
      }),
    );

    renderWithProviders(<HealthCard />);

    expect(screen.getByRole('status')).toHaveTextContent('Checking backend status');
    expect(screen.getByRole('button', { name: 'Check again' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(await screen.findByText('Operational')).toBeInTheDocument();
  });

  it('shows service, version, server time and database when the backend is UP', async () => {
    renderWithProviders(<HealthCard />);

    expect(await screen.findByText('Operational')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'System status' })).toBeInTheDocument();
    expect(screen.getByText('tech-interviewer')).toBeInTheDocument();
    expect(screen.getByText('0.1.0')).toBeInTheDocument();
    expect(screen.getByText('Database').nextElementSibling).toHaveTextContent('UP');
    expect(document.querySelector('time')).toHaveAttribute('datetime', healthUp.serverTime);
  });

  it('shows database DOWN when the backend answers 503 with a HealthResponse', async () => {
    server.use(healthHandlers.down);

    renderWithProviders(<HealthCard />);

    expect(await screen.findByText('Degraded')).toBeInTheDocument();
    expect(screen.getByText('Database').nextElementSibling).toHaveTextContent('DOWN');
    expect(screen.getByText('0.1.0')).toBeInTheDocument();
  });

  it('explains how to start the backend when it cannot be reached', async () => {
    server.use(healthHandlers.networkError);

    renderWithProviders(<HealthCard />);

    expect(await screen.findByText('Unreachable')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('The backend cannot be reached');
    expect(screen.getByText(/docker compose up -d/)).toHaveTextContent(
      'cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local',
    );
  });

  it('treats a 503 problem response as unreachable and shows the trace id', async () => {
    server.use(healthHandlers.problem);

    renderWithProviders(<HealthCard />);

    expect(await screen.findByText('Unreachable')).toBeInTheDocument();
    expect(screen.getByText(/trace-12345678/)).toBeInTheDocument();
  });

  it('checks again on demand and recovers when the backend comes back', async () => {
    const user = userEvent.setup();
    server.use(healthHandlers.networkError);
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Unreachable')).toBeInTheDocument();

    server.use(healthHandlers.up);
    await user.click(screen.getByRole('button', { name: 'Check again' }));

    expect(await screen.findByText('Operational')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Check again' })).toBeEnabled();
    });
  });

  it('keeps keyboard focus on the button and ignores clicks while a refresh is running', async () => {
    const user = userEvent.setup();
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Operational')).toBeInTheDocument();

    let requests = 0;
    let release: () => void = () => undefined;
    server.use(
      http.get(HEALTH_URL, async () => {
        requests += 1;
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return HttpResponse.json(healthUp);
      }),
    );
    screen.getByRole('button', { name: 'Check again' }).focus();
    await user.keyboard('{Enter}');

    const busy = await screen.findByRole('button', { name: /Checking/ });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).not.toBeDisabled();
    expect(busy).toHaveFocus();
    await user.click(busy);
    await user.keyboard('{Enter}');
    expect(requests).toBe(1);

    release();
    const idle = await screen.findByRole('button', { name: 'Check again' });
    expect(idle).toHaveAttribute('aria-disabled', 'false');
    expect(idle).toHaveFocus();
  });

  it('is a plain button that never submits a surrounding form', async () => {
    renderWithProviders(<HealthCard />);

    expect(await screen.findByRole('button', { name: 'Check again' })).toHaveAttribute(
      'type',
      'button',
    );
  });

  it('does not retry the health check with the production query client', async () => {
    server.use(healthHandlers.networkError);
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    render(
      <QueryClientProvider client={createQueryClient()}>
        <HealthCard />
      </QueryClientProvider>,
    );

    expect(await screen.findByText('Unreachable')).toBeInTheDocument();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('aborts the in-flight request when the card goes away', async () => {
    server.use(
      http.get(HEALTH_URL, async () => {
        await new Promise(() => undefined);
        return HttpResponse.json(healthUp);
      }),
    );
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { unmount } = renderWithProviders(<HealthCard />);
    await waitFor(() => {
      expect(fetchSpy).toHaveBeenCalledTimes(1);
    });
    const signal = fetchSpy.mock.calls[0]?.[1]?.signal;
    expect(signal?.aborted).toBe(false);

    unmount();

    await waitFor(() => {
      expect(signal?.aborted).toBe(true);
    });
  });

  it('reports an unresponsive backend as unreachable instead of waiting forever', async () => {
    vi.spyOn(AbortSignal, 'timeout').mockImplementation(() =>
      AbortSignal.abort(new DOMException('timed out', 'TimeoutError')),
    );

    renderWithProviders(<HealthCard />);

    expect(await screen.findByText('Unreachable')).toBeInTheDocument();
    expect(screen.getByText(/The backend did not answer in time/)).toBeInTheDocument();
  });

  it('announces only the summary, politely, and keeps the details out of the live region', async () => {
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Operational')).toBeInTheDocument();

    const status = screen.getByRole('status');
    expect(status).toHaveAttribute('aria-live', 'polite');
    expect(status).toHaveTextContent('Operational');
    expect(status).toHaveTextContent('The backend and its database are responding.');
    expect(within(status).queryByText('tech-interviewer')).toBeNull();
    expect(within(status).queryByRole('button')).toBeNull();
    expect(within(status).queryByRole('heading')).toBeNull();
  });

  it('keeps the start instructions out of the live region when the backend is unreachable', async () => {
    server.use(healthHandlers.networkError);
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Unreachable')).toBeInTheDocument();

    const status = screen.getByRole('status');
    expect(status).toHaveAttribute('aria-live', 'polite');
    expect(within(status).queryByText(/docker compose up -d/)).toBeNull();
    expect(within(status).queryByText(/Reason:/)).toBeNull();
  });

  it('colours each state with its own tone', async () => {
    const { unmount } = renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Operational')).toHaveAttribute('data-tone', 'success');
    expect(screen.getByText('UP')).toHaveAttribute('data-tone', 'success');
    unmount();

    server.use(healthHandlers.down);
    const degraded = renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Degraded')).toHaveAttribute('data-tone', 'danger');
    expect(screen.getByText('DOWN')).toHaveAttribute('data-tone', 'danger');
    degraded.unmount();

    server.use(healthHandlers.networkError);
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Unreachable')).toHaveAttribute('data-tone', 'neutral');
  });

  it('formats the server time for the reader and keeps the ISO value in the dateTime attribute', async () => {
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Operational')).toBeInTheDocument();

    const expected = new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(new Date(healthUp.serverTime));
    const time = document.querySelector('time');
    expect(time).toHaveTextContent(expected);
    expect(time?.textContent).not.toBe(healthUp.serverTime);
  });

  it('shows a server time that cannot be parsed exactly as received', async () => {
    server.use(
      http.get(HEALTH_URL, () => HttpResponse.json({ ...healthUp, serverTime: 'yesterday-ish' })),
    );
    renderWithProviders(<HealthCard />);

    expect(await screen.findByText('yesterday-ish')).toHaveAttribute('datetime', 'yesterday-ish');
  });

  it('gives the reason without a trace id when the failure has none', async () => {
    server.use(healthHandlers.networkError);
    renderWithProviders(<HealthCard />);

    expect(await screen.findByText(/Reason:/)).toHaveTextContent(
      /^Reason: The backend could not be reached\.$/,
    );
  });

  it('gives the reason and the trace id of a problem response', async () => {
    server.use(healthHandlers.problem);
    renderWithProviders(<HealthCard />);

    expect(await screen.findByText(/Reason:/)).toHaveTextContent(
      /^Reason: Internal Server Error \(trace trace-12345678\)$/,
    );
  });

  it('refetches automatically every 30 seconds', async () => {
    let requests = 0;
    server.use(
      http.get(HEALTH_URL, () => {
        requests += 1;
        return HttpResponse.json(healthUp);
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderWithProviders(<HealthCard />);
    expect(await screen.findByText('Operational')).toBeInTheDocument();
    expect(requests).toBe(1);

    await vi.advanceTimersByTimeAsync(29_000);
    expect(requests).toBe(1);

    await vi.advanceTimersByTimeAsync(2_000);
    await waitFor(() => {
      expect(requests).toBe(2);
    });
  });
});
