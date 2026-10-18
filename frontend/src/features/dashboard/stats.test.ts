import { describe, expect, it } from 'vitest';

import { report, summary } from '../../test/fixtures';
import { must } from '../../test/must';
import { summarize } from './stats';

const at = (day: number) => `2026-10-${String(day).padStart(2, '0')}T12:00:00Z`;

function entry(id: string, day: number, overrides: Parameters<typeof report>[0] = {}) {
  return {
    summary: summary({ id, endedAt: at(day) }),
    report: report({ interviewId: id, ...overrides }),
  };
}

describe('summarize', () => {
  it('is empty without reports', () => {
    const stats = summarize([]);
    expect(stats).toMatchObject({
      average: null,
      best: null,
      latest: null,
      change: null,
      sinceFirst: null,
    });
    expect(stats.scored).toEqual([]);
  });

  it('orders interviews by when they ended, oldest first, and computes simple statistics of the scores', () => {
    const stats = summarize([
      entry('c', 9, { overallScore: 80 }),
      entry('a', 1, { overallScore: 50 }),
      entry('b', 5, { overallScore: 65 }),
    ]);

    expect(stats.scored.map((item) => item.summary.id)).toEqual(['a', 'b', 'c']);
    expect(stats.average).toBe(65);
    expect(stats.best).toBe(80);
    expect(stats.latest).toBe(80);
    expect(stats.change).toBe(15);
    expect(stats.sinceFirst).toBe(30);
  });

  it('ignores reports that are not READY or have no score, instead of counting them as zero', () => {
    const stats = summarize([
      entry('a', 1, { overallScore: 70 }),
      entry('b', 2, { status: 'FAILED', overallScore: null }),
      entry('c', 3, { outcome: 'INCONCLUSIVE', overallScore: null, recommendation: null }),
      { summary: summary({ id: 'd' }), report: undefined },
    ]);

    expect(stats.scored).toHaveLength(1);
    expect(stats.average).toBe(70);
    expect(stats.change).toBeNull();
    expect(stats.sinceFirst).toBeNull();
  });

  it('weights a technology average by the number of answers each report stood on', () => {
    const skill = (score: number, evidenceCount: number) => ({
      skill: 'Java',
      slug: 'java',
      score,
      confidence: 'HIGH' as const,
      evidenceCount,
      avgDifficulty: 3,
    });
    const stats = summarize([
      entry('a', 1, { skills: [skill(100, 1)] }),
      entry('b', 2, { skills: [skill(40, 3)] }),
    ]);

    expect(stats.skills).toEqual([
      { slug: 'java', name: 'Java', score: 55, answers: 4, interviews: 2 },
    ]);
  });

  it('counts in how many interviews a topic was weak, most often first', () => {
    const weak = (key: string, score: number) => ({
      topic: { key, name: key },
      score,
      evidenceCount: 1,
    });
    const gaps = (topics: ReturnType<typeof weak>[]) => ({
      ...must(report().knowledgeGaps),
      weakTopics: topics,
    });
    const stats = summarize([
      entry('a', 1, { knowledgeGaps: gaps([weak('aop', 30), weak('sql', 40)]) }),
      entry('b', 2, { knowledgeGaps: gaps([weak('aop', 20)]) }),
    ]);

    expect(stats.weaknesses.map((item) => [item.key, item.interviews, item.averageScore])).toEqual([
      ['aop', 2, 25],
      ['sql', 1, 40],
    ]);
  });

  it('takes strengths and weaknesses from the most recent reports only, newest first, capped', () => {
    const narrative = (text: string) => [{ text, evidence: [] }];
    const stats = summarize([
      entry('a', 1, { strengths: narrative('oldest') }),
      entry('b', 2, { strengths: narrative('older') }),
      entry('c', 3, { strengths: narrative('middle') }),
      entry('d', 4, { strengths: narrative('newest') }),
    ]);

    expect(stats.strengths.map((item) => item.text)).toEqual(['newest', 'middle', 'older']);
  });
});
