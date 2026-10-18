import type { Recommendation } from '../api/types';

/**
 * Colour bands for a 0 to 100 score. They repeat the backend's recommendation thresholds (80, 65, 45) so a colour never
 * disagrees with the recommendation next to it; they decide colour only and never a verdict.
 */
export type ScoreBand = 'strong' | 'proficient' | 'developing' | 'weak';

export function scoreBand(score: number): ScoreBand {
  if (score >= 80) {
    return 'strong';
  }
  if (score >= 65) {
    return 'proficient';
  }
  return score >= 45 ? 'developing' : 'weak';
}

export const BAND_FILL: Record<ScoreBand, string> = {
  strong: 'var(--tone-success-solid)',
  proficient: 'var(--tone-brand)',
  developing: 'var(--tone-warning-solid)',
  weak: 'var(--tone-danger-solid)',
};

export const BAND_TEXT: Record<ScoreBand, string> = {
  strong: 'text-success-ink',
  proficient: 'text-brand-soft-ink',
  developing: 'text-warning-ink',
  weak: 'text-danger-ink',
};

export const RECOMMENDATION_TONE: Record<
  Recommendation,
  'success' | 'brand' | 'warning' | 'danger'
> = {
  STRONG: 'success',
  PROFICIENT: 'brand',
  DEVELOPING: 'warning',
  NOT_YET: 'danger',
};

/** A 0 to 4 rating of one criterion as a 0 to 100 share, for drawing a bar. */
export function ratingShare(rating: number): number {
  return (rating / 4) * 100;
}
