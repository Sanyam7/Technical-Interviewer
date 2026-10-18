import { QueryClient } from '@tanstack/react-query';

import { ApiError } from '../api/http';

const STALE_TIME_MS = 30_000;
const MAX_RETRIES = 2;

function shouldRetry(failureCount: number, error: Error): boolean {
  const isClientError = error instanceof ApiError && error.status >= 400 && error.status < 500;
  return !isClientError && failureCount < MAX_RETRIES;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: STALE_TIME_MS, retry: shouldRetry },
    },
  });
}
