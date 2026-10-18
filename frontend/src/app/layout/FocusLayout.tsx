import { Outlet } from 'react-router';

import { SkipLink } from './SkipLink';

/** The interview room: no navigation to wander off with, the page itself carries the interview's header. */
export function FocusLayout() {
  return (
    <div className="flex min-h-dvh flex-col">
      <SkipLink />
      <main id="main-content" tabIndex={-1} className="flex flex-1 flex-col outline-none">
        <Outlet />
      </main>
    </div>
  );
}
