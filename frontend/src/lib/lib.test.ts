import { describe, expect, it } from 'vitest';

import { ApiError } from '../api/http';
import { describeError, formErrors } from './errors';
import { formatClock, formatScore, plural } from './format';
import { workspacePath } from './labels';
import { ratingShare, scoreBand } from './scoring';

describe('formatClock', () => {
  it.each([
    [0, '00:00'],
    [59, '00:59'],
    [754, '12:34'],
    [3725, '1:02:05'],
    [-5, '00:00'],
    [61.9, '01:01'],
  ])('%s seconds is %s', (seconds, text) => {
    expect(formatClock(seconds)).toBe(text);
  });
});

describe('formatScore and plural', () => {
  it('drops trailing zeros but keeps one decimal', () => {
    expect(formatScore(72)).toBe('72');
    expect(formatScore(72.5)).toBe('72.5');
    expect(formatScore(72.45)).toBe('72.5');
  });

  it('pluralises', () => {
    expect(plural(1, 'answer')).toBe('1 answer');
    expect(plural(2, 'answer')).toBe('2 answers');
    expect(plural(0, 'family', 'families')).toBe('0 families');
  });
});

describe('scoreBand', () => {
  it('uses the recommendation thresholds of the backend (80, 65, 45)', () => {
    expect(scoreBand(80)).toBe('strong');
    expect(scoreBand(79.99)).toBe('proficient');
    expect(scoreBand(65)).toBe('proficient');
    expect(scoreBand(64.99)).toBe('developing');
    expect(scoreBand(45)).toBe('developing');
    expect(scoreBand(44.99)).toBe('weak');
  });

  it('maps a 0 to 4 rating to a share of a bar', () => {
    expect(ratingShare(0)).toBe(0);
    expect(ratingShare(3)).toBe(75);
    expect(ratingShare(4)).toBe(100);
  });
});

describe('workspacePath', () => {
  it('has a page per section', () => {
    expect(workspacePath('i1', 'CODING')).toBe('/interviews/i1/coding');
    expect(workspacePath('i1', 'SYSTEM_DESIGN')).toBe('/interviews/i1/design');
    expect(workspacePath('i1', 'QUESTIONING')).toBe('/interviews/i1/live');
    expect(workspacePath('i1', 'INTRODUCTION')).toBe('/interviews/i1/live');
    expect(workspacePath('i1', null)).toBe('/interviews/i1/live');
  });
});

describe('describeError', () => {
  const api = (status: number, code: string, detail?: string) =>
    new ApiError({ status, code, title: code, detail, traceId: 't-1' });

  it('separates a network failure from a timeout and says both can be retried', () => {
    expect(describeError(api(0, 'NETWORK_ERROR'))).toMatchObject({
      title: 'Cannot reach the server',
      retryable: true,
    });
    expect(describeError(api(0, 'TIMEOUT'))).toMatchObject({
      title: 'The server took too long',
      retryable: true,
    });
  });

  it('explains an unavailable AI service and a rate limit', () => {
    expect(describeError(api(503, 'AI_PROVIDER_UNAVAILABLE'))).toMatchObject({
      title: 'The AI interviewer is unavailable',
      retryable: true,
    });
    expect(describeError(api(429, 'RATE_LIMITED', 'Slow down'))).toMatchObject({
      title: 'Too many requests',
      message: 'Slow down',
    });
  });

  it('does not offer a retry for a missing resource or a rejected request, and keeps the trace id', () => {
    expect(describeError(api(404, 'NOT_FOUND'))).toMatchObject({
      retryable: false,
      traceId: 't-1',
    });
    expect(describeError(api(409, 'QUESTION_NOT_CURRENT', 'Moved on'))).toMatchObject({
      message: 'Moved on',
      retryable: false,
    });
  });

  it('never shows internals of a server error', () => {
    const description = describeError(
      api(500, 'INTERNAL_ERROR', 'java.lang.NullPointerException at X'),
    );
    expect(description.message).not.toContain('NullPointerException');
    expect(description.retryable).toBe(true);
  });

  it('copes with something that is not an ApiError', () => {
    expect(describeError(new Error('boom')).title).toBe('Something went wrong');
  });
});

describe('formErrors', () => {
  const failure = new ApiError({
    status: 400,
    code: 'VALIDATION_FAILED',
    title: 'Validation failed',
    errors: [
      { field: 'configuration.skills[1]', message: 'unknown skill' },
      { field: 'configuration.durationMinutes', message: 'must be 15, 30, 45 or 60' },
      { field: 'configuration.role', message: 'must not be null' },
      { field: 'configuration', message: 'is required' },
    ],
  });

  it('files each error under the form field at the root of its path, after the prefix', () => {
    const { fields, unshown } = formErrors(
      failure,
      ['skills', 'durationMinutes'],
      'configuration.',
    );
    expect(fields).toEqual({
      skills: 'unknown skill',
      durationMinutes: 'must be 15, 30, 45 or 60',
    });
    expect(unshown).toEqual(['role: must not be null', 'configuration: is required']);
  });

  it('returns nothing for a failure that is not about fields', () => {
    expect(formErrors(new Error('x'), ['email'])).toEqual({ fields: {}, unshown: [] });
  });
});
