import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';

import { routes } from '../app/router';
import { AuthProvider } from '../features/auth/AuthProvider';
import { server } from './server';
import { authHandlers } from './handlers';

function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

/** Renders a component with the query client. Components that call useAuth need `{ auth: true }` (it looks for a session). */
export function renderWithProviders(ui: ReactElement, { auth = false }: { auth?: boolean } = {}) {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      {auth ? <AuthProvider>{ui}</AuthProvider> : ui}
    </QueryClientProvider>,
  );
}

interface RenderRouteOptions {
  /** The visitor has a valid refresh cookie, so the page load signs them in. */
  signedIn?: boolean;
}

export function renderRoute(path: string, { signedIn = false }: RenderRouteOptions = {}) {
  if (signedIn) {
    server.use(authHandlers.signedIn);
  }
  const router = createMemoryRouter(routes, {
    initialEntries: [path],
  });
  const queryClient = createTestQueryClient();
  const view = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return { ...view, router, queryClient };
}
