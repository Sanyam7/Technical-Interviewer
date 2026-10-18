import type { Report, Section } from '../../api/types';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { formatScore } from '../../lib/format';
import { BAND_TEXT, scoreBand } from '../../lib/scoring';

const SECTION_TITLE: Record<Section, string> = {
  INTRODUCTION: 'Introduction',
  QUESTIONING: 'Technical',
  CODING: 'Coding',
  SYSTEM_DESIGN: 'System design',
};

const SECTION_DESCRIPTION: Record<Section, string> = {
  INTRODUCTION: 'How you presented yourself',
  QUESTIONING: 'Concept questions and follow-ups',
  CODING: 'Problem solving in code',
  SYSTEM_DESIGN: 'Architecture and trade-offs',
};

const BAR_TONE = {
  strong: 'success',
  proficient: 'brand',
  developing: 'warning',
  weak: 'danger',
} as const;

/** One score per section of the interview, so a strong coding round does not hide a weak design round. */
export function SectionScores({ report }: { report: Report }) {
  if (report.sections.length === 0) {
    return null;
  }
  return (
    <section aria-label="Scores by section">
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {report.sections.map(({ section, score }) => {
          const band = scoreBand(score);
          return (
            <li
              key={section}
              className="border-line bg-surface shadow-raised rounded-2xl border p-5"
            >
              <h3 className="text-sm font-semibold">{SECTION_TITLE[section]} score</h3>
              <p className="text-ink-muted text-xs">{SECTION_DESCRIPTION[section]}</p>
              <p
                className={`mt-3 text-3xl font-bold tracking-tight tabular-nums ${BAND_TEXT[band]}`}
              >
                {formatScore(score)}
              </p>
              <ProgressBar
                className="mt-3"
                label={`${SECTION_TITLE[section]} score`}
                value={score}
                tone={BAR_TONE[band]}
              />
            </li>
          );
        })}
      </ul>
    </section>
  );
}
