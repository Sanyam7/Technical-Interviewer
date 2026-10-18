import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { API_ORIGIN } from '../test/handlers';
import { server } from '../test/server';
import { ApiError, apiFetch, REQUEST_TIMEOUT_MS } from './http';

const URL_ECHO = `${API_ORIGIN}/api/v1/echo`;

describe('apiFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.stubEnv('VITE_API_BASE_URL', API_ORIGIN);
  });

  it('returns the parsed JSON body and sends Accept and X-Request-Id headers', async () => {
    let received: Request | undefined;
    server.use(
      http.get(URL_ECHO, ({ request }) => {
        received = request;
        return HttpResponse.json({ ok: true });
      }),
    );

    const result = await apiFetch<{ ok: boolean }>('/api/v1/echo');

    expect(result).toEqual({ ok: true });
    expect(received?.headers.get('Accept')).toBe('application/json');
    expect(received?.headers.get('X-Request-Id')).toMatch(/^[A-Za-z0-9._-]{8,64}$/);
  });

  it('sets a JSON content type for string bodies', async () => {
    let contentType: string | null = null;
    server.use(
      http.post(URL_ECHO, ({ request }) => {
        contentType = request.headers.get('Content-Type');
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const result = await apiFetch<unknown>('/api/v1/echo', {
      method: 'POST',
      body: JSON.stringify({ a: 1 }),
    });

    expect(result).toBeUndefined();
    expect(contentType).toBe('application/json');
  });

  it('parses an RFC 9457 problem body into an ApiError with every field', async () => {
    server.use(
      http.get(URL_ECHO, () =>
        HttpResponse.json(
          {
            type: 'https://techinterviewer.dev/problems/validation-failed',
            title: 'Validation failed',
            status: 400,
            detail: 'One or more fields are invalid.',
            instance: '/api/v1/echo',
            code: 'VALIDATION_FAILED',
            traceId: 'trace-12345678',
            errors: [{ field: 'email', message: 'must be a valid email' }, { field: 'ignored' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await apiFetch('/api/v1/echo').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      title: 'Validation failed',
      detail: 'One or more fields are invalid.',
      traceId: 'trace-12345678',
      errors: [{ field: 'email', message: 'must be a valid email' }],
    });
  });

  it('turns a non-JSON error body into an ApiError with an unexpected-response code', async () => {
    server.use(
      http.get(URL_ECHO, () =>
        HttpResponse.text('<html>Bad gateway</html>', {
          status: 502,
          statusText: 'Bad Gateway',
          headers: { 'X-Request-Id': 'proxy-request-1' },
        }),
      ),
    );

    const error = await apiFetch('/api/v1/echo').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 502,
      code: 'UNEXPECTED_RESPONSE',
      title: 'Bad Gateway',
      traceId: 'proxy-request-1',
      errors: [],
    });
  });

  it('rejects a successful response whose body is not JSON', async () => {
    server.use(http.get(URL_ECHO, () => HttpResponse.text('plain text')));

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      status: 200,
    });
  });

  it('maps a network failure to NETWORK_ERROR', async () => {
    server.use(http.get(URL_ECHO, () => HttpResponse.error()));

    const error = await apiFetch('/api/v1/echo').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });

  it('joins the base URL and the path without duplicate or missing slashes', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(Response.json({})));

    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com/');
    await apiFetch('/api/v1/health');
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com');
    await apiFetch('api/v1/health');
    vi.stubEnv('VITE_API_BASE_URL', '');
    await apiFetch('/api/v1/health');

    expect(fetchSpy.mock.calls.map(([url]) => url)).toEqual([
      'https://api.example.com/api/v1/health',
      'https://api.example.com/api/v1/health',
      '/api/v1/health',
    ]);
  });

  it('passes the AbortSignal through and rethrows the native abort error', async () => {
    server.use(
      http.get(URL_ECHO, async () => {
        await new Promise(() => undefined);
        return HttpResponse.json({});
      }),
    );
    const controller = new AbortController();

    const pending = apiFetch('/api/v1/echo', { signal: controller.signal });
    controller.abort();

    const error: unknown = await pending.catch((e: unknown) => e);
    expect(error).not.toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ name: 'AbortError' });
  });
  it('treats an unset VITE_API_BASE_URL as same-origin instead of failing', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(Response.json({ ok: true })));
    vi.stubEnv('VITE_API_BASE_URL', undefined);

    await expect(apiFetch('/api/v1/health')).resolves.toEqual({ ok: true });

    expect(fetchSpy.mock.calls.map(([url]) => url)).toEqual(['/api/v1/health']);
  });

  it('does not disguise a programming error as a network failure', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    const error: unknown = await apiFetch(undefined as never).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(TypeError);
    expect(error).not.toBeInstanceOf(ApiError);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('keeps the original failure as the cause of a network error', async () => {
    const failure = new TypeError('Failed to fetch');
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(failure);

    const error = await apiFetch('/api/v1/echo').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 0,
      code: 'NETWORK_ERROR',
      title: 'Network error',
      detail: 'The backend could not be reached.',
      message: 'The backend could not be reached.',
      cause: failure,
    });
  });

  it('rethrows an abort error that fetch raised, but not a look-alike', async () => {
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(abort);
    await expect(apiFetch('/api/v1/echo')).rejects.toBe(abort);

    const notADomException = Object.assign(new Error('x'), { name: 'AbortError' });
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(notADomException);
    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({ code: 'NETWORK_ERROR' });

    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new DOMException('x', 'NetworkError'));
    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });

  it('rethrows whatever fetch raised when the caller has already aborted', async () => {
    const failure = new Error('socket closed');
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(failure);
    const controller = new AbortController();
    controller.abort();

    await expect(apiFetch('/api/v1/echo', { signal: controller.signal })).rejects.toBe(failure);
  });

  it('gives every request a timeout and reports an expired one as TIMEOUT', async () => {
    const timeoutSpy = vi
      .spyOn(AbortSignal, 'timeout')
      .mockImplementation(() => AbortSignal.abort(new DOMException('timed out', 'TimeoutError')));
    server.use(http.get(URL_ECHO, () => HttpResponse.json({})));

    const error = await apiFetch('/api/v1/echo').catch((e: unknown) => e);

    expect(REQUEST_TIMEOUT_MS).toBe(10_000);
    expect(timeoutSpy).toHaveBeenCalledWith(10_000);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 0,
      code: 'TIMEOUT',
      title: 'Request timed out',
      detail: 'The backend did not answer in time.',
    });
    expect((error as ApiError).cause).toMatchObject({ name: 'TimeoutError' });
  });

  it('applies the timeout to a request that also has a caller signal', async () => {
    vi.spyOn(AbortSignal, 'timeout').mockImplementation(() =>
      AbortSignal.abort(new DOMException('timed out', 'TimeoutError')),
    );
    server.use(http.get(URL_ECHO, () => HttpResponse.json({})));
    const controller = new AbortController();

    await expect(apiFetch('/api/v1/echo', { signal: controller.signal })).rejects.toMatchObject({
      code: 'TIMEOUT',
    });
    expect(controller.signal.aborted).toBe(false);
  });

  it('keeps the timeout when the caller supplies a signal, and the caller can still abort', async () => {
    const timeoutSpy = vi.spyOn(AbortSignal, 'timeout');
    const received: (AbortSignal | null | undefined)[] = [];
    server.use(
      http.get(URL_ECHO, async ({ request }) => {
        received.push(request.signal);
        await new Promise(() => undefined);
        return HttpResponse.json({});
      }),
    );
    const controller = new AbortController();

    const pending = apiFetch('/api/v1/echo', { signal: controller.signal });
    await vi.waitFor(() => {
      expect(received).toHaveLength(1);
    });
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(timeoutSpy).toHaveBeenCalledWith(REQUEST_TIMEOUT_MS);
  });

  it('prefers the trace id in the problem body over the X-Request-Id header', async () => {
    server.use(
      http.get(URL_ECHO, () =>
        HttpResponse.json(
          { title: 'Boom', code: 'BOOM', traceId: 'body-trace-1' },
          { status: 500, headers: { 'X-Request-Id': 'header-trace-1' } },
        ),
      ),
    );

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({ traceId: 'body-trace-1' });
  });

  it('falls back to the X-Request-Id header when the problem body has no trace id', async () => {
    server.use(
      http.get(URL_ECHO, () =>
        HttpResponse.json(
          { title: 'Boom', code: 'BOOM' },
          { status: 500, headers: { 'X-Request-Id': 'header-trace-1' } },
        ),
      ),
    );

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({ traceId: 'header-trace-1' });
  });

  it('has no trace id when neither the body nor a header provides one', async () => {
    server.use(http.get(URL_ECHO, () => HttpResponse.json({ title: 'Boom' }, { status: 500 })));
    server.use(http.post(URL_ECHO, () => HttpResponse.text('oops', { status: 500 })));

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({ traceId: undefined });
    await expect(apiFetch('/api/v1/echo', { method: 'POST' })).rejects.toMatchObject({
      traceId: undefined,
    });
  });

  it('uses the problem detail, then the title, as the error message', async () => {
    server.use(
      http.get(URL_ECHO, () =>
        HttpResponse.json({ title: 'Boom', detail: 'It broke.' }, { status: 500 }),
      ),
      http.post(URL_ECHO, () => HttpResponse.json({ title: 'Boom' }, { status: 500 })),
    );

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({
      message: 'It broke.',
      title: 'Boom',
      detail: 'It broke.',
    });
    await expect(apiFetch('/api/v1/echo', { method: 'POST' })).rejects.toMatchObject({
      message: 'Boom',
      detail: undefined,
    });
  });

  it('defaults the code and the title of a JSON error body that is not a problem document', async () => {
    server.use(http.get(URL_ECHO, () => HttpResponse.json({ unrelated: true }, { status: 418 })));

    const error = await apiFetch('/api/v1/echo').catch((e: unknown) => e);

    expect(error).toMatchObject({
      status: 418,
      code: 'UNEXPECTED_RESPONSE',
      title: "I'm a Teapot",
      body: { unrelated: true },
    });
  });

  it('falls back to "HTTP <status>" when the response has no status text', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 502, statusText: '' }),
    );

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({
      status: 502,
      title: 'HTTP 502',
      code: 'UNEXPECTED_RESPONSE',
    });
  });

  it('copes with a JSON null error body and with junk entries in the error list', async () => {
    server.use(
      http.get(URL_ECHO, () => HttpResponse.json(null, { status: 500 })),
      http.post(URL_ECHO, () =>
        HttpResponse.json(
          { title: 'Bad', code: 'BAD', errors: ['junk', null, 5, { field: 'a', message: 'b' }] },
          { status: 400 },
        ),
      ),
    );

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({
      status: 500,
      code: 'UNEXPECTED_RESPONSE',
      body: null,
    });
    await expect(apiFetch('/api/v1/echo', { method: 'POST' })).rejects.toMatchObject({
      code: 'BAD',
      errors: [{ field: 'a', message: 'b' }],
    });
  });

  it('does not treat a JSON array error body as a problem document', async () => {
    server.use(
      http.get(URL_ECHO, () => HttpResponse.json([{ title: 'x', code: 'Y' }], { status: 400 })),
    );

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({
      status: 400,
      code: 'UNEXPECTED_RESPONSE',
      body: [{ title: 'x', code: 'Y' }],
    });
  });

  it('describes a success body that is not JSON', async () => {
    server.use(http.get(URL_ECHO, () => HttpResponse.text('plain text')));

    await expect(apiFetch('/api/v1/echo')).rejects.toMatchObject({
      title: 'Invalid response',
      detail: 'The backend returned a body that is not valid JSON.',
    });
  });

  it('keeps a content type chosen by the caller and every caller header', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(Response.json({})));

    await apiFetch('/api/v1/echo', {
      method: 'POST',
      body: 'a=1',
      headers: { 'Content-Type': 'text/plain', Authorization: 'Bearer token' },
    });

    const init = fetchSpy.mock.calls[0]?.[1];
    const headers = new Headers(init?.headers);
    expect(init?.method).toBe('POST');
    expect(init?.body).toBe('a=1');
    expect(headers.get('Content-Type')).toBe('text/plain');
    expect(headers.get('Authorization')).toBe('Bearer token');
    expect(headers.get('Accept')).toBe('application/json');
  });

  it('sets no content type when there is no string body', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(Response.json({})));

    await apiFetch('/api/v1/echo');
    await apiFetch('/api/v1/echo', { method: 'POST', body: new Uint8Array([1, 2]) });

    for (const [, init] of fetchSpy.mock.calls) {
      expect(new Headers(init?.headers).has('Content-Type')).toBe(false);
    }
  });

  it('uses randomUUID when the browser provides it', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => Promise.resolve(Response.json({})));
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-2222-4333-8444-555555555555');

    await apiFetch('/api/v1/echo');

    expect(new Headers(fetchSpy.mock.calls[0]?.[1]?.headers).get('X-Request-Id')).toBe(
      '11111111-2222-4333-8444-555555555555',
    );
  });

  it.each([
    [Array.from({ length: 16 }, (_, index) => index), '00010203-0405-4607-8809-0a0b0c0d0e0f'],
    [new Array<number>(16).fill(255), 'ffffffff-ffff-4fff-bfff-ffffffffffff'],
    [new Array<number>(16).fill(0), '00000000-0000-4000-8000-000000000000'],
  ])(
    'builds a v4 request id from getRandomValues where randomUUID is missing (non-secure context)',
    async (random, expected) => {
      const fetchSpy = vi
        .spyOn(globalThis, 'fetch')
        .mockImplementation(() => Promise.resolve(Response.json({})));
      vi.stubGlobal('crypto', {
        getRandomValues: <T extends ArrayBufferView>(array: T): T => {
          new Uint8Array(array.buffer).set(random);
          return array;
        },
      });

      await expect(apiFetch('/api/v1/echo')).resolves.toEqual({});

      expect(new Headers(fetchSpy.mock.calls[0]?.[1]?.headers).get('X-Request-Id')).toBe(expected);
    },
  );
});
