import { useQuery, queryOptions } from '@tanstack/react-query';

import { apiFetch, ApiError, INVALID_RESPONSE_CODE } from './http';
import type { components } from './schema';

/**
 * Body of GET /api/v1/health (HTTP 200 when healthy, 503 when the database is down).
 * Generated from the backend's OpenAPI document with `pnpm gen:api`.
 */
export type HealthResponse = components['schemas']['HealthResponse'];
export type ComponentStatus = HealthResponse['status'];

export const HEALTH_REFETCH_INTERVAL_MS = 30_000;

function isComponentStatus(value: unknown): value is ComponentStatus {
  return value === 'UP' || value === 'DOWN';
}

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    isComponentStatus(candidate.status) &&
    isComponentStatus(candidate.database) &&
    typeof candidate.service === 'string' &&
    typeof candidate.version === 'string' &&
    typeof candidate.serverTime === 'string'
  );
}

function invalidHealthBody(status: number): ApiError {
  return new ApiError({
    status,
    code: INVALID_RESPONSE_CODE,
    title: 'Invalid response',
    detail: 'The health endpoint returned an unexpected body.',
  });
}

/** A 503 that carries a HealthResponse is a valid "database DOWN" answer, not a failure. */
export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  try {
    const body = await apiFetch<unknown>('/api/v1/health', { signal });
    if (!isHealthResponse(body)) {
      throw invalidHealthBody(200);
    }
    return body;
  } catch (error) {
    if (error instanceof ApiError && error.status === 503 && isHealthResponse(error.body)) {
      return error.body;
    }
    throw error;
  }
}

export const healthQueryOptions = queryOptions({
  queryKey: ['health'],
  queryFn: ({ signal }) => getHealth(signal),
  refetchInterval: HEALTH_REFETCH_INTERVAL_MS,
  retry: false,
});

export function useHealth() {
  return useQuery(healthQueryOptions);
}
