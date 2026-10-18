import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { StatusBadge } from './StatusBadge';

describe('StatusBadge', () => {
  it.each([
    ['success', '✓', 'bg-success-bg', 'text-success-ink'],
    ['danger', '✕', 'bg-danger-bg', 'text-danger-ink'],
    ['neutral', '•', 'bg-neutral-bg', 'text-neutral-ink'],
  ] as const)(
    'renders the %s tone with its own colours and a non-colour mark',
    (tone, mark, background, ink) => {
      render(<StatusBadge tone={tone}>Label</StatusBadge>);

      const badge = screen.getByText('Label');
      expect(badge).toHaveAttribute('data-tone', tone);
      expect(badge).toHaveClass(background, ink);
      const markElement = badge.querySelector('[aria-hidden="true"]');
      expect(markElement).toHaveTextContent(mark);
    },
  );
});
