import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { authFetch, jsonBody } from './session';
import type { Profile, UpdateProfileRequest } from './types';

export const profileQuery = queryOptions({
  queryKey: ['profile'],
  queryFn: ({ signal }) => authFetch<Profile>('/api/v1/profile', { signal }),
});

export function useProfile() {
  return useQuery(profileQuery);
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateProfileRequest) =>
      authFetch<Profile>('/api/v1/profile', jsonBody(request, 'PUT')),
    onSuccess: (profile) => {
      queryClient.setQueryData(profileQuery.queryKey, profile);
    },
  });
}
