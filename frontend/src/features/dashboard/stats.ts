import type { InterviewSummary, Report } from '../../api/types';

export interface ScoredInterview {
  summary: InterviewSummary;
  report: Report;
  /** Always set: only reports with an overall score are used. */
  score: number;
  /** When the interview ended, for ordering; falls back to when it started or was created. */
  at: string;
}

export interface SkillAverage {
  slug: string;
  name: string;
  /** Mean of the report scores, weighted by how many answers each stood on. */
  score: number;
  answers: number;
  interviews: number;
}

export interface RecurringWeakness {
  key: string;
  name: string;
  /** In how many interviews this topic was weak. */
  interviews: number;
  averageScore: number;
}

export interface NarrativeEntry {
  text: string;
  at: string;
  interviewId: string;
}

export interface DashboardStats {
  /** Oldest first, for drawing over time. */
  scored: ScoredInterview[];
  average: number | null;
  best: number | null;
  latest: number | null;
  /** Latest minus the interview before it. */
  change: number | null;
  /** Latest minus the first one: how far you have come. */
  sinceFirst: number | null;
  skills: SkillAverage[];
  weaknesses: RecurringWeakness[];
  strengths: NarrativeEntry[];
  improvements: NarrativeEntry[];
}

const NARRATIVE_LIMIT = 4;
const RECENT_FOR_NARRATIVE = 3;

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

/**
 * Descriptive statistics over the reports the backend produced. Nothing here scores anything: every number is either one of
 * the backend's scores or a plain mean of them. Only READY reports with an overall score count (an INCONCLUSIVE report has
 * none, and is not a zero).
 */
export function summarize(
  entries: { summary: InterviewSummary; report: Report | undefined }[],
): DashboardStats {
  const scored: ScoredInterview[] = entries
    .flatMap(({ summary, report }) =>
      report?.status === 'READY' && report.overallScore != null
        ? [
            {
              summary,
              report,
              score: report.overallScore,
              at: summary.endedAt ?? summary.startedAt ?? summary.createdAt,
            },
          ]
        : [],
    )
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));

  const scores = scored.map((entry) => entry.score);
  const latest = scores.at(-1) ?? null;
  const previous = scores.at(-2);
  const first = scores[0];

  const skillTotals = new Map<
    string,
    { name: string; weighted: number; answers: number; interviews: number }
  >();
  for (const { report } of scored) {
    for (const skill of report.skills) {
      const total = skillTotals.get(skill.slug) ?? {
        name: skill.skill,
        weighted: 0,
        answers: 0,
        interviews: 0,
      };
      total.weighted += skill.score * skill.evidenceCount;
      total.answers += skill.evidenceCount;
      total.interviews += 1;
      skillTotals.set(skill.slug, total);
    }
  }
  const skills = [...skillTotals.entries()]
    .filter(([, total]) => total.answers > 0)
    .map(([slug, total]) => ({
      slug,
      name: total.name,
      score: round1(total.weighted / total.answers),
      answers: total.answers,
      interviews: total.interviews,
    }))
    .sort((a, b) => b.score - a.score);

  const weakTotals = new Map<string, { name: string; interviews: number; scoreSum: number }>();
  for (const { report } of scored) {
    for (const weak of report.knowledgeGaps?.weakTopics ?? []) {
      const total = weakTotals.get(weak.topic.key) ?? {
        name: weak.topic.name,
        interviews: 0,
        scoreSum: 0,
      };
      total.interviews += 1;
      total.scoreSum += weak.score;
      weakTotals.set(weak.topic.key, total);
    }
  }
  const weaknesses = [...weakTotals.entries()]
    .map(([key, total]) => ({
      key,
      name: total.name,
      interviews: total.interviews,
      averageScore: round1(total.scoreSum / total.interviews),
    }))
    .sort((a, b) => b.interviews - a.interviews || a.averageScore - b.averageScore);

  const recent = scored.slice(-RECENT_FOR_NARRATIVE).reverse();
  const narrative = (pick: (report: Report) => { text: string }[]): NarrativeEntry[] =>
    recent
      .flatMap((entry) =>
        pick(entry.report).map((item) => ({
          text: item.text,
          at: entry.at,
          interviewId: entry.summary.id,
        })),
      )
      .slice(0, NARRATIVE_LIMIT);

  return {
    scored,
    average:
      scores.length > 0
        ? round1(scores.reduce((sum, value) => sum + value, 0) / scores.length)
        : null,
    best: scores.length > 0 ? Math.max(...scores) : null,
    latest,
    change: latest !== null && previous !== undefined ? round1(latest - previous) : null,
    sinceFirst:
      latest !== null && first !== undefined && scores.length > 1 ? round1(latest - first) : null,
    skills,
    weaknesses,
    strengths: narrative((report) => report.strengths),
    improvements: narrative((report) => report.improvementAreas),
  };
}
