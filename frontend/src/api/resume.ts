import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from './http';
import { authFetch } from './session';
import type { Resume } from './types';

const PATH = '/api/v1/profile/resume';

/**
 * Reading a résumé is one model call over the whole document: seconds with Gemini, minutes with a small local model. The
 * backend's budget for it is 270 s at most, so the request waits a little longer than that.
 */
export const RESUME_UPLOAD_TIMEOUT_MS = 300_000;

/** The largest file the backend accepts (2 MiB); checked here too so a large file is refused before it is sent. */
export const MAX_RESUME_BYTES = 2 * 1024 * 1024;

/** The candidate's résumé, or null when none was uploaded (the API answers 404). */
export const resumeQuery = queryOptions({
  queryKey: ['profile', 'resume'],
  queryFn: async ({ signal }): Promise<Resume | null> => {
    try {
      return await authFetch<Resume>(PATH, { signal });
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }
      throw error;
    }
  },
});

export function useResume() {
  return useQuery(resumeQuery);
}

export function useUploadResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return authFetch<Resume>(PATH, {
        method: 'PUT',
        body: form,
        timeoutMs: RESUME_UPLOAD_TIMEOUT_MS,
      });
    },
    onSuccess: (resume) => {
      queryClient.setQueryData(resumeQuery.queryKey, resume);
    },
  });
}

export function useDeleteResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => authFetch<undefined>(PATH, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.setQueryData(resumeQuery.queryKey, null);
    },
  });
}
