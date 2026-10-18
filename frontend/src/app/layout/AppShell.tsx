import { useState } from 'react';
import { NavLink, Outlet } from 'react-router';

import { Button } from '../../components/ui/Button';
import { Icon, type IconName } from '../../components/ui/Icon';
import { cx } from '../../lib/cx';
import { useAuth, useUser } from '../../features/auth/authContext';
import { PRODUCT_NAME, Wordmark } from '../brand';
import { SkipLink } from './SkipLink';
import { useFocusOnNavigate } from './useFocusOnNavigate';

interface NavItem {
  to: string;
  label: string;
  icon: IconName;
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: 'home' },
  { to: '/interviews/new', label: 'New interview', icon: 'plus' },
  { to: '/interviews', label: 'History', icon: 'history', end: true },
  { to: '/profile', label: 'Profile', icon: 'user' },
];

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <ul className="space-y-1">
      {NAV.map((item) => (
        <li key={item.to}>
          <NavLink
            to={item.to}
            end={item.end ?? false}
            onClick={onNavigate}
            className={({ isActive }) =>
              cx(
                'flex min-h-11 items-center gap-3 rounded-lg px-3 text-sm font-semibold transition-colors',
                isActive
                  ? 'bg-brand-soft text-brand-soft-ink'
                  : 'text-ink-muted hover:bg-surface-muted hover:text-ink',
              )
            }
          >
            <Icon name={item.icon} />
            {item.label}
          </NavLink>
        </li>
      ))}
    </ul>
  );
}

function AccountBlock() {
  const user = useUser();
  const { signOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);

  return (
    <div className="border-line border-t pt-4">
      <p className="truncate text-sm font-semibold">{user.displayName}</p>
      <p className="text-ink-muted truncate text-xs">{user.email}</p>
      <Button
        variant="ghost"
        size="sm"
        className="mt-3 w-full justify-start"
        icon={<Icon name="logout" className="size-4" />}
        loading={signingOut}
        onClick={() => {
          setSigningOut(true);
          void signOut();
        }}
      >
        Sign out
      </Button>
    </div>
  );
}

/** The signed-in application: a sidebar on wide screens, a top bar with a menu on narrow ones. */
export function AppShell() {
  const [menuOpen, setMenuOpen] = useState(false);
  useFocusOnNavigate();

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[16rem_minmax(0,1fr)]">
      <SkipLink />
      <aside className="border-line bg-surface hidden border-r lg:block">
        <div className="sticky top-0 flex h-dvh flex-col gap-6 p-4">
          <Wordmark to="/dashboard" className="px-2 pt-2 text-[0.95rem]" />
          <nav aria-label="Main" className="flex-1">
            <NavLinks />
          </nav>
          <AccountBlock />
        </div>
      </aside>

      <div className="min-w-0">
        <header className="border-line bg-page/85 sticky top-0 z-30 border-b backdrop-blur lg:hidden">
          <div className="flex items-center justify-between gap-3 px-4 py-2.5">
            <Wordmark to="/dashboard" className="text-sm" />
            <Button
              variant="secondary"
              size="sm"
              aria-expanded={menuOpen}
              aria-controls="mobile-menu"
              icon={<Icon name={menuOpen ? 'x' : 'menu'} className="size-4" />}
              onClick={() => {
                setMenuOpen((open) => !open);
              }}
            >
              Menu
            </Button>
          </div>
          {menuOpen ? (
            <div id="mobile-menu" className="border-line bg-surface space-y-4 border-t p-4">
              <nav aria-label="Main">
                <NavLinks
                  onNavigate={() => {
                    setMenuOpen(false);
                  }}
                />
              </nav>
              <AccountBlock />
            </div>
          ) : null}
        </header>

        <main
          id="main-content"
          tabIndex={-1}
          className="mx-auto w-full max-w-6xl px-4 py-8 outline-none sm:px-6 lg:px-10 lg:py-10"
        >
          <Outlet />
        </main>
        <footer className="text-ink-muted mx-auto max-w-6xl px-4 pb-8 text-xs sm:px-6 lg:px-10">
          {PRODUCT_NAME} · practice interviews are scored by AI against a fixed rubric.
        </footer>
      </div>
    </div>
  );
}
