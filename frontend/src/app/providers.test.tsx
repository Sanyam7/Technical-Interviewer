import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import { AppProviders } from './providers';

describe('AppProviders', () => {
  it('creates one QueryClient and keeps it across re-renders', () => {
    const seen: QueryClient[] = [];
    function Probe() {
      seen.push(useQueryClient());
      return null;
    }
    const router = createMemoryRouter([{ path: '/', element: <Probe /> }]);

    const { rerender } = render(<AppProviders router={router} />);
    rerender(<AppProviders router={router} />);
    rerender(<AppProviders router={router} />);

    expect(seen.length).toBeGreaterThan(0);
    expect(new Set(seen).size).toBe(1);
  });
});
