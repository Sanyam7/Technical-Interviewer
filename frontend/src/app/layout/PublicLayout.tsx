import { Link, Outlet } from 'react-router';

import { useAuth } from '../../features/auth/authContext';
import { ButtonLink } from '../../components/ui/Button';
import { PRODUCT_NAME, Wordmark } from '../brand';
import { SkipLink } from './SkipLink';
import { useFocusOnNavigate } from './useFocusOnNavigate';

/** Landing, sign-in and sign-up: the page chrome of a visitor. */
export function PublicLayout() {
  const { state } = useAuth();
  useFocusOnNavigate();

  return (
    <div className="flex min-h-dvh flex-col">
      <SkipLink />
      <header className="border-line bg-page/80 sticky top-0 z-30 border-b backdrop-blur">
        <div className="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <Wordmark />
          <nav aria-label="Account" className="flex items-center gap-2">
            {state.status === 'authenticated' ? (
              <ButtonLink to="/dashboard" size="sm">
                Dashboard
              </ButtonLink>
            ) : (
              <>
                <ButtonLink to="/login" variant="ghost" size="sm">
                  Sign in
                </ButtonLink>
                <ButtonLink to="/register" size="sm">
                  Get started
                </ButtonLink>
              </>
            )}
          </nav>
        </div>
      </header>
      <main id="main-content" tabIndex={-1} className="flex-1 outline-none">
        <Outlet />
      </main>
      <footer className="border-line text-ink-muted border-t text-sm">
        <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-6 sm:px-6">
          <span>{PRODUCT_NAME}</span>
          <Link to="/status" className="hover:text-ink underline-offset-4 hover:underline">
            System status
          </Link>
        </div>
      </footer>
    </div>
  );
}
