import type { StageMix } from '../../api/types';

/** The stage shares a CUSTOM interview starts from. */
export const DEFAULT_CUSTOM_MIX: StageMix = { fundamentals: 50, practical: 30, project: 20 };

export function mixTotal(mix: StageMix): number {
  return mix.fundamentals + mix.practical + mix.project;
}

/** "60% fundamentals · 25% practical · 15% projects". */
export function describeMix(mix: StageMix): string {
  return `${mix.fundamentals}% fundamentals · ${mix.practical}% practical · ${mix.project}% projects`;
}
