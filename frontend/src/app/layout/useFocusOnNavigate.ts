import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router';

/**
 * A single-page app does not move focus when the page changes, so a keyboard or screen-reader user stays on the link they
 * used. After every navigation (not the first render) focus goes to the main region.
 */
export function useFocusOnNavigate(): void {
  const { pathname } = useLocation();
  const previous = useRef(pathname);

  useEffect(() => {
    if (previous.current !== pathname) {
      previous.current = pathname;
      document.getElementById('main-content')?.focus({ preventScroll: false });
    }
  }, [pathname]);
}
