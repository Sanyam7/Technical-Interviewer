import { queryOptions, useQuery } from '@tanstack/react-query';

import { apiFetch } from './http';
import type { CatalogOptions, CatalogSkill } from './types';

const ONE_HOUR_MS = 3_600_000;

/** The catalog is public and cacheable for an hour (API_DESIGN.md section 3). */
export const catalogOptionsQuery = queryOptions({
  queryKey: ['catalog', 'options'],
  queryFn: ({ signal }) => apiFetch<CatalogOptions>('/api/v1/catalog/options', { signal }),
  staleTime: ONE_HOUR_MS,
});

export const catalogSkillsQuery = queryOptions({
  queryKey: ['catalog', 'skills'],
  queryFn: ({ signal }) => apiFetch<CatalogSkill[]>('/api/v1/catalog/skills', { signal }),
  staleTime: ONE_HOUR_MS,
});

export function useCatalogOptions() {
  return useQuery(catalogOptionsQuery);
}

export function useCatalogSkills() {
  return useQuery(catalogSkillsQuery);
}
