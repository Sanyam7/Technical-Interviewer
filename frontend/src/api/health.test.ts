import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { HEALTH_URL, healthDown, healthHandlers, healthUp } from '../test/handlers';
import { server } from '../test/server';
import { ApiError } from './http';
import { getHealth, healthQueryOptions, HEALTH_REFETCH_INTERVAL_MS } from './health';

describe('getHealth', () => {
  it('returns the body of a 200 response', async () => {
    await expect(getHealth()).resolves.toEqual(healthUp);
  });

  it('treats a 503 with a HealthResponse body as data, not as an error', async () => {
    server.use(healthHandlers.down);

    await expect(getHealth()).resolves.toEqual(healthDown);
  });

  it('throws an ApiError for a 503 with a problem+json body', async () => {
    server.use(healthHandlers.problem);

    const error = await getHealth().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 503, code: 'INTERNAL_ERROR', traceId: 'trace-12345678' });
  });

  it('throws an ApiError for a 200 whose body is not a HealthResponse', async () => {
    server.use(http.get(HEALTH_URL, () => HttpResponse.json({ status: 'UP' })));

    const error = await getHealth().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 200,
      code: 'INVALID_RESPONSE',
      title: 'Invalid response',
      detail: 'The health endpoint returned an unexpected body.',
    });
  });

  it.each(['status', 'database', 'service', 'version', 'serverTime'] as const)(
    'rejects a body that lacks only the %s field',
    async (field) => {
      const incomplete = Object.fromEntries(
        Object.entries(healthUp).filter(([key]) => key !== field),
      );
      server.use(http.get(HEALTH_URL, () => HttpResponse.json(incomplete)));

      await expect(getHealth()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    },
  );

  it.each([
    ['status', 'WEIRD'],
    ['status', 'up'],
    ['status', 1],
    ['database', 'UNKNOWN'],
    ['database', true],
    ['service', 7],
    ['version', null],
    ['serverTime', 1_790_000_000],
  ] as const)('rejects a body whose %s is %j', async (field, value) => {
    server.use(http.get(HEALTH_URL, () => HttpResponse.json({ ...healthUp, [field]: value })));

    await expect(getHealth()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it.each([
    ['null', null],
    ['a string', 'UP'],
    ['an array', [healthUp]],
  ] as const)('rejects a body that is %s', async (_label, body) => {
    server.use(http.get(HEALTH_URL, () => HttpResponse.json(body)));

    await expect(getHealth()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it.each([500, 502, 504, 404])(
    'does not accept a HealthResponse carried by an HTTP %i',
    async (status) => {
      server.use(http.get(HEALTH_URL, () => HttpResponse.json(healthUp, { status })));

      const error = await getHealth().catch((e: unknown) => e);

      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status, code: 'UNEXPECTED_RESPONSE' });
    },
  );

  it('does not accept a 503 whose body is not a HealthResponse', async () => {
    server.use(
      http.get(HEALTH_URL, () =>
        HttpResponse.json({ ...healthDown, status: 'WEIRD' }, { status: 503 }),
      ),
    );

    await expect(getHealth()).rejects.toMatchObject({ status: 503, code: 'UNEXPECTED_RESPONSE' });
  });

  it('forwards the AbortSignal so that an abort cancels the request', async () => {
    let requestSignal: AbortSignal | undefined;
    server.use(
      http.get(HEALTH_URL, async ({ request }) => {
        requestSignal = request.signal;
        await new Promise(() => undefined);
        return HttpResponse.json(healthUp);
      }),
    );
    const controller = new AbortController();

    const pending = getHealth(controller.signal);
    await vi.waitFor(() => {
      expect(requestSignal).toBeDefined();
    });
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('throws an ApiError for a network failure', async () => {
    server.use(healthHandlers.networkError);

    await expect(getHealth()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });
});

describe('healthQueryOptions', () => {
  it('polls every 30 seconds', () => {
    expect(HEALTH_REFETCH_INTERVAL_MS).toBe(30_000);
    expect(healthQueryOptions.refetchInterval).toBe(HEALTH_REFETCH_INTERVAL_MS);
  });
});
