import { useEffect } from 'react';

import { PRODUCT_NAME } from '../brand';

/** Sets the browser tab title, which is also what a screen reader announces on a page change. */
export function usePageTitle(title: string | null | undefined): void {
  useEffect(() => {
    document.title = title ? `${title} · ${PRODUCT_NAME}` : PRODUCT_NAME;
  }, [title]);
}
