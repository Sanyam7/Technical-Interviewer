import { act, render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { question, session } from '../../test/fixtures';
import { QuestionCard } from './QuestionCard';
import { Timer } from './RoomHeader';
import { StreamedText } from './StreamedText';
import { useCountdown, useTimeUp } from './useCountdown';
import { clearStoredDraft, useDraft } from './useDraft';

afterEach(() => {
  vi.useRealTimers();
});

describe('StreamedText', () => {
  const TEXT = 'word '.repeat(200).trim();

  it('shows everything at once when it is not animated', () => {
    render(<StreamedText text="Full question" animate={false} />);

    expect(screen.getByText('Full question')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show full question' })).not.toBeInTheDocument();
  });

  it('reveals the text over time, always keeping the whole text for screen readers, and finishes by itself', () => {
    vi.useFakeTimers();
    render(<StreamedText text={TEXT} animate />);

    expect(screen.getByRole('button', { name: 'Show full question' })).toBeInTheDocument();
    const full = document.querySelector('p.sr-only');
    expect(full).toHaveTextContent(TEXT);
    const visible = () => document.querySelector('[aria-hidden="true"]')?.textContent.length ?? 0;
    expect(visible()).toBe(0);

    act(() => {
      vi.advanceTimersByTime(500);
    });
    const partway = visible();
    expect(partway).toBeGreaterThan(0);
    expect(partway).toBeLessThan(TEXT.length);

    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(screen.queryByRole('button', { name: 'Show full question' })).not.toBeInTheDocument();
    expect(screen.getByText(TEXT)).toBeInTheDocument();
  });

  it('can be skipped', async () => {
    const user = userEvent.setup();
    render(<StreamedText text={TEXT} animate />);

    await user.click(screen.getByRole('button', { name: 'Show full question' }));

    expect(screen.getByText(TEXT)).toBeInTheDocument();
  });

  it('is not animated for people who ask for reduced motion', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('reduce'),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    render(<StreamedText text="Straight away" animate />);

    expect(screen.getByText('Straight away')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show full question' })).not.toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});

describe('useDraft', () => {
  it('saves after a pause, restores for the same question and forgets on request', () => {
    vi.useFakeTimers();
    const first = renderHook(() => useDraft('q-1', ''));

    act(() => {
      first.result.current.setValue('thinking');
    });
    expect(localStorage.getItem('ti.draft.q-1')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(localStorage.getItem('ti.draft.q-1')).toBe('thinking');
    first.unmount();

    const second = renderHook(() => useDraft('q-1', 'starter'));
    expect(second.result.current.value).toBe('thinking');

    clearStoredDraft('q-1');
    expect(localStorage.getItem('ti.draft.q-1')).toBeNull();
    const third = renderHook(() => useDraft('q-2', 'starter'));
    expect(third.result.current.value).toBe('starter');
  });

  it('keeps working when storage is unavailable', () => {
    vi.useFakeTimers();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { result } = renderHook(() => useDraft('q-1', 'start'));

    expect(result.current.value).toBe('start');
    act(() => {
      result.current.setValue('typed');
      vi.advanceTimersByTime(500);
    });
    expect(result.current.value).toBe('typed');
  });
});

describe('useCountdown', () => {
  it('counts down from the server clock, whatever the browser clock says', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2030-01-01T00:00:00Z'));
    const received = Date.now();
    const live = session({
      serverTime: '2026-10-08T12:10:00Z',
      deadlineAt: '2026-10-08T12:30:00Z',
    });
    const { result } = renderHook(() => useCountdown(live, received));

    expect(result.current.remainingSeconds).toBe(1200);
    act(() => {
      vi.advanceTimersByTime(65_000);
    });
    expect(result.current.remainingSeconds).toBe(1135);
    expect(result.current.expired).toBe(false);
  });

  it('expires at zero and stays there', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-08T12:00:00Z'));
    const live = session({
      serverTime: '2026-10-08T12:00:00Z',
      deadlineAt: '2026-10-08T12:00:03Z',
    });
    const received = Date.now();
    const { result } = renderHook(() => useCountdown(live, received));

    act(() => {
      vi.advanceTimersByTime(10_000);
    });

    expect(result.current).toEqual({ remainingSeconds: 0, expired: true });
  });

  it('has no clock before the interview started', () => {
    const idle = session({ state: 'INIT', deadlineAt: null, startedAt: null });
    const { result } = renderHook(() => useCountdown(idle, Date.now()));

    expect(result.current).toEqual({ remainingSeconds: null, expired: false });
  });
});

describe('useTimeUp', () => {
  it('re-renders its component once, when the time is up, not every second', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-08T12:00:00Z'));
    const live = session({
      serverTime: '2026-10-08T12:00:00Z',
      deadlineAt: '2026-10-08T12:00:05Z',
    });
    const received = Date.now();
    let renders = 0;
    const { result } = renderHook(() => {
      renders += 1;
      return useTimeUp(live, received);
    });
    const initialRenders = renders;

    act(() => {
      vi.advanceTimersByTime(4_000);
    });
    expect(result.current).toBe(false);
    expect(renders).toBe(initialRenders);

    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(result.current).toBe(true);
    expect(renders).toBe(initialRenders + 1);
  });
});

describe('Timer', () => {
  it('formats the time and announces only when it gets short', () => {
    const { rerender } = render(<Timer remainingSeconds={1200} />);
    expect(screen.getByRole('timer', { name: 'Time remaining' })).toHaveTextContent('20:00');
    expect(screen.getByRole('status')).toBeEmptyDOMElement();

    rerender(<Timer remainingSeconds={290} />);
    expect(screen.getByRole('status')).toHaveTextContent('Less than five minutes left.');

    rerender(<Timer remainingSeconds={45} />);
    expect(screen.getByRole('status')).toHaveTextContent('Less than one minute left.');
    expect(screen.getByRole('timer')).toHaveClass('text-danger-ink');
  });

  it('shows a placeholder before the clock starts', () => {
    render(<Timer remainingSeconds={null} />);

    expect(screen.getByRole('timer')).toHaveTextContent('--:--');
  });
});

describe('QuestionCard', () => {
  it('says what kind of technical question it is', () => {
    const { rerender } = render(
      <QuestionCard fresh={false} question={question({ questionType: 'INTERNALS' })} />,
    );
    expect(screen.getByText('Fundamentals')).toBeInTheDocument();

    rerender(<QuestionCard fresh={false} question={question({ questionType: 'DEBUGGING' })} />);
    expect(screen.getByText('Debugging')).toBeInTheDocument();

    rerender(<QuestionCard fresh={false} question={question({ questionType: null })} />);
    expect(screen.queryByText('Fundamentals')).not.toBeInTheDocument();
  });

  it('shows the résumé statement a personalised question is based on', () => {
    render(
      <QuestionCard
        fresh={false}
        question={question({
          resume: {
            kind: 'PROJECT',
            text: 'Built a Spring Boot microservice using Kafka to process card payment events.',
            context: 'Acme Payments',
          },
        })}
      />,
    );

    expect(screen.getByText('From your résumé')).toBeInTheDocument();
    expect(screen.getByText('Based on your résumé · Acme Payments')).toBeInTheDocument();
    expect(
      screen.getByText(
        '“Built a Spring Boot microservice using Kafka to process card payment events.”',
      ),
    ).toBeInTheDocument();
  });

  it('says nothing about a résumé for an ordinary question', () => {
    render(<QuestionCard fresh={false} question={question()} />);

    expect(screen.queryByText(/résumé/)).not.toBeInTheDocument();
  });
});
