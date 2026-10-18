import { QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { RouterProvider } from 'react-router/dom';

import { AuthProvider } from '../features/auth/AuthProvider';
import { createQueryClient } from './queryClient';
import type { createAppRouter } from './router';

interface AppProvidersProps {
  router: ReturnType<typeof createAppRouter>;
}

export function AppProviders({ router }: AppProvidersProps) {
  const [queryClient] = useState(createQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  );
}
