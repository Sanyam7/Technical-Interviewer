import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';

import { Button } from '../../components/ui/Button';
import { Spinner } from '../../components/ui/Spinner';
import { describeError } from '../../lib/errors';
import { useAuth } from './authContext';

function FullPageMessage({ children }: { children: ReactNode }) {
  return <div className="grid min-h-dvh place-items-center p-6 text-center">{children}</div>;
}

/** Everything below it needs a signed-in user; anonymous visitors are sent to the sign-in page and back afterwards. */
export function RequireAuth() {
  const { state, retry } = useAuth();
  const location = useLocation();

  switch (state.status) {
    case 'loading':
      return (
        <FullPageMessage>
          <div role="status" className="text-ink-muted flex items-center gap-3">
            <Spinner />
            <span>Checking your session…</span>
          </div>
        </FullPageMessage>
      );
    case 'unavailable':
      return (
        <FullPageMessage>
          <div role="alert" className="max-w-sm">
            <h1 className="text-xl font-semibold">{describeError(state.error).title}</h1>
            <p className="text-ink-muted mt-2 text-sm">{describeError(state.error).message}</p>
            <Button className="mt-5" onClick={retry}>
              Try again
            </Button>
          </div>
        </FullPageMessage>
      );
    case 'anonymous':
      return <Navigate to="/login" replace state={{ from: location }} />;
    case 'authenticated':
      return <Outlet />;
  }
}

/** Sign-in and sign-up are for visitors: a signed-in user goes where they were heading, or to the dashboard. */
export function GuestOnly() {
  const { state } = useAuth();
  const location = useLocation();

  if (state.status === 'authenticated') {
    // The whole address the visitor wanted, with its query and fragment (a filtered list, a report section).
    const from = (
      location.state as { from?: { pathname?: string; search?: string; hash?: string } } | null
    )?.from;
    const target = from?.pathname
      ? { pathname: from.pathname, search: from.search ?? '', hash: from.hash ?? '' }
      : '/dashboard';
    return <Navigate to={target} replace />;
  }
  return <Outlet />;
}
