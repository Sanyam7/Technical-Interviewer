import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { must } from '../../test/must';
import { BarList } from './BarList';
import { RadarChart } from './RadarChart';
import { ScoreRing } from './ScoreRing';
import { TrendChart } from './TrendChart';

describe('ScoreRing', () => {
  it('reads as one labelled image with the score, and shows the number', () => {
    render(<ScoreRing score={72.5} label="Overall score" />);

    expect(screen.getByRole('img', { name: 'Overall score: 72.5 out of 100' })).toBeInTheDocument();
    expect(screen.getByText('72.5')).toBeInTheDocument();
  });

  it('does not draw more than a full ring for a score above 100 or below 0', () => {
    const { container, rerender } = render(<ScoreRing score={140} label="x" />);
    const arc = () => container.querySelectorAll('circle')[1];
    expect(Number(arc()?.getAttribute('stroke-dashoffset'))).toBeCloseTo(0);

    rerender(<ScoreRing score={-5} label="x" />);
    const circumference = Number(arc()?.getAttribute('stroke-dasharray'));
    expect(Number(arc()?.getAttribute('stroke-dashoffset'))).toBeCloseTo(circumference);
  });
});

describe('BarList', () => {
  it('lists every row with its label and number as text, clamping the bar', () => {
    render(
      <BarList
        items={[
          { key: 'a', label: 'Java', value: 78, detail: '4 answers' },
          { key: 'b', label: 'React', value: 130 },
          { key: 'c', label: 'Go', value: 0, display: 'n/a' },
        ]}
      />,
    );

    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(within(must(rows[0])).getByText('78')).toBeInTheDocument();
    expect(within(must(rows[0])).getByText('4 answers')).toBeInTheDocument();
    expect(within(must(rows[2])).getByText('n/a')).toBeInTheDocument();
    const bars = document.querySelectorAll<HTMLElement>('[role="presentation"] > div');
    expect(bars[1]?.style.width).toBe('100%');
  });
});

describe('TrendChart', () => {
  it('describes the trend and offers the same numbers as a table', () => {
    render(
      <TrendChart
        label="Overall score"
        points={[
          { key: 'a', label: 'Oct 1', value: 50 },
          { key: 'b', label: 'Oct 5', value: 65 },
          { key: 'c', label: 'Oct 9', value: 80 },
        ]}
      />,
    );

    expect(
      screen.getByRole('img', { name: 'Overall score: 3 values, from 50 to 80' }),
    ).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Overall score' });
    expect(within(table).getAllByRole('row')).toHaveLength(4);
    expect(within(table).getByRole('rowheader', { name: 'Oct 5' })).toBeInTheDocument();
  });

  it('draws a single point without a line', () => {
    const { container } = render(
      <TrendChart label="Overall score" points={[{ key: 'a', label: 'Oct 1', value: 70 }]} />,
    );

    expect(container.querySelector('polyline')).toBeNull();
    expect(container.querySelectorAll('circle')).toHaveLength(1);
  });

  it('keeps the axis fixed at 0 to 100 so a small change does not look big', () => {
    render(
      <TrendChart
        label="Overall score"
        points={[
          { key: 'a', label: 'A', value: 70 },
          { key: 'b', label: 'B', value: 71 },
        ]}
      />,
    );

    for (const tick of ['0', '25', '50', '75', '100']) {
      expect(screen.getByText(tick, { selector: 'text' })).toBeInTheDocument();
    }
  });
});

describe('RadarChart', () => {
  it('names every axis and value for assistive technology', () => {
    render(
      <RadarChart
        label="Skill scores"
        axes={[
          { key: 'a', label: 'Java', value: 80 },
          { key: 'b', label: 'SQL', value: 60 },
          { key: 'c', label: 'React', value: 40 },
        ]}
      />,
    );

    expect(
      screen.getByRole('img', { name: 'Skill scores: Java 80, SQL 60, React 40' }),
    ).toBeInTheDocument();
  });
});
