import { describe, expect, it } from 'vitest';

import { ApiError } from '../api/http';
import { createQueryClient } from './queryClient';

function apiError(status: number): ApiError {
  return new ApiError({ status, code: 'TEST', title: `HTTP ${status}` });
}

function retryPolicy(): (failureCount: number, error: Error) => boolean {
  const { retry } = createQueryClient().getDefaultOptions().queries ?? {};
  if (typeof retry !== 'function') {
    throw new Error('the production QueryClient must define a retry function');
  }
  return retry;
}

describe('createQueryClient', () => {
  it('keeps fetched data fresh for 30 seconds', () => {
    expect(createQueryClient().getDefaultOptions().queries?.staleTime).toBe(30_000);
  });

  it('never retries a 4xx ApiError', () => {
    const retry = retryPolicy();

    for (const status of [400, 401, 404, 422, 499]) {
      expect(retry(0, apiError(status)), `status ${status}`).toBe(false);
    }
  });

  it('retries 5xx and network failures up to twice and then stops', () => {
    const retry = retryPolicy();

    for (const error of [apiError(500), apiError(503), apiError(0), apiError(399)]) {
      expect(retry(0, error), `${error.message} at 0`).toBe(true);
      expect(retry(1, error), `${error.message} at 1`).toBe(true);
      expect(retry(2, error), `${error.message} at 2`).toBe(false);
      expect(retry(3, error), `${error.message} at 3`).toBe(false);
    }
  });

  it('retries a plain Error up to twice', () => {
    const retry = retryPolicy();

    expect(retry(0, new Error('boom'))).toBe(true);
    expect(retry(1, new TypeError('boom'))).toBe(true);
    expect(retry(2, new Error('boom'))).toBe(false);
  });

  it('applies the policy to real queries: one attempt for a 404, three for a 503', async () => {
    const attempts = { notFound: 0, unavailable: 0 };
    const client = createQueryClient();

    await expect(
      client.query({
        queryKey: ['not-found'],
        retryDelay: 0,
        queryFn: () => {
          attempts.notFound += 1;
          return Promise.reject(apiError(404));
        },
      }),
    ).rejects.toMatchObject({ status: 404 });
    await expect(
      client.query({
        queryKey: ['unavailable'],
        retryDelay: 0,
        queryFn: () => {
          attempts.unavailable += 1;
          return Promise.reject(apiError(503));
        },
      }),
    ).rejects.toMatchObject({ status: 503 });

    expect(attempts).toEqual({ notFound: 1, unavailable: 3 });
  });
});
