import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../api/http';
import { Button } from './Button';
import { Dialog } from './Dialog';
import { ErrorState } from './ErrorState';
import { TextField } from './Field';
import { Markdown } from './Markdown';

describe('Markdown', () => {
  it('renders formatting and code', () => {
    render(<Markdown>{'A **bold** word and `code`.\n\n- one\n- two'}</Markdown>);

    expect(screen.getByText('bold').tagName).toBe('STRONG');
    expect(screen.getByText('code').tagName).toBe('CODE');
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('never renders raw HTML, links or images from model or candidate text', () => {
    const { container } = render(
      <Markdown>
        {
          '<script>alert(1)</script><img src="http://evil.test/x.png" onerror="alert(1)">\n\n[click me](http://evil.test)\n\n![pixel](http://evil.test/p.png)'
        }
      </Markdown>,
    );

    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
    expect(container).toHaveTextContent('click me');
  });

  it('shows headings as plain emphasis so the page outline stays under the page title', () => {
    const { container } = render(<Markdown>{'# Big\n\n## Smaller'}</Markdown>);

    expect(container.querySelector('h1, h2')).toBeNull();
    expect(screen.getByText('Big')).toBeInTheDocument();
  });
});

describe('Button', () => {
  it('blocks repeated clicks while it works and says so', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Save
      </Button>,
    );

    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('TextField', () => {
  it('ties label, hint and error to the input', () => {
    render(<TextField label="Email" hint="We never share it." error="Not an email" />);

    const input = screen.getByLabelText('Email');
    expect(input).toHaveAccessibleDescription('We never share it. Not an email');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('Not an email');
  });
});

describe('Dialog', () => {
  it('opens as a labelled modal and reports closing', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const { rerender } = render(
      <Dialog
        open={false}
        onClose={onClose}
        title="Sure?"
        description="This cannot be undone."
        footer={
          <button type="button" onClick={onClose}>
            OK
          </button>
        }
      />,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    rerender(
      <Dialog
        open
        onClose={onClose}
        title="Sure?"
        description="This cannot be undone."
        footer={
          <button type="button" onClick={onClose}>
            OK
          </button>
        }
      />,
    );

    const dialog = screen.getByRole('dialog', { name: 'Sure?' });
    expect(dialog).toHaveAccessibleDescription('This cannot be undone.');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(onClose).toHaveBeenCalled();
  });
});

describe('ErrorState', () => {
  it('offers a retry only for errors that a retry can fix, and shows the reference', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = render(
      <ErrorState
        error={
          new ApiError({ status: 500, code: 'INTERNAL_ERROR', title: 'x', traceId: 'trace-1' })
        }
        onRetry={onRetry}
      />,
    );

    expect(screen.getByText('Reference: trace-1')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    rerender(
      <ErrorState
        error={new ApiError({ status: 404, code: 'NOT_FOUND', title: 'x' })}
        onRetry={onRetry}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });
});
