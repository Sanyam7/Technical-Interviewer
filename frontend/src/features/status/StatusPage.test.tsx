import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../test/renderWithProviders';
import { StatusPage } from './StatusPage';

describe('StatusPage', () => {
  it('shows the system status card', async () => {
    renderWithProviders(<StatusPage />);

    expect(screen.getByRole('heading', { level: 1, name: 'Service status' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'System status' })).toBeInTheDocument();
    expect(await screen.findByText('Operational')).toBeInTheDocument();
  });
});
