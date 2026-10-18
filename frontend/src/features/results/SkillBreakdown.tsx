import type { Report } from '../../api/types';
import { BarList } from '../../components/charts/BarList';
import { RadarChart } from '../../components/charts/RadarChart';
import { Badge } from '../../components/ui/Badge';
import { Card } from '../../components/ui/Card';
import { plural } from '../../lib/format';

const CONFIDENCE_LABEL = {
  LOW: 'Low confidence',
  MEDIUM: 'Medium confidence',
  HIGH: 'High confidence',
} as const;
const CONFIDENCE_TONE = { LOW: 'warning', MEDIUM: 'neutral', HIGH: 'success' } as const;

/** Scores per technology: a radar for the balance, bars for the numbers, and how much evidence each stands on. */
export function SkillBreakdown({ report }: { report: Report }) {
  const skills = [...report.skills].sort((a, b) => b.score - a.score);
  if (skills.length === 0) {
    return null;
  }

  return (
    <Card
      title="Topic scores"
      description="One score per technology, weighted by question difficulty. Fewer answers mean a less certain score."
    >
      <div className="grid items-center gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        {skills.length >= 3 ? (
          <div className="flex justify-center">
            <RadarChart
              label="Skill scores"
              axes={skills.map((skill) => ({
                key: skill.slug,
                label: skill.skill,
                value: skill.score,
              }))}
            />
          </div>
        ) : null}
        <div className={skills.length >= 3 ? undefined : 'lg:col-span-2'}>
          <BarList
            items={skills.map((skill) => ({
              key: skill.slug,
              label: skill.skill,
              value: skill.score,
              detail: (
                <span className="inline-flex flex-wrap items-center gap-2">
                  <Badge tone={CONFIDENCE_TONE[skill.confidence]}>
                    {CONFIDENCE_LABEL[skill.confidence]}
                  </Badge>
                  <span>{plural(skill.evidenceCount, 'answer')} assessed</span>
                </span>
              ),
            }))}
          />
        </div>
      </div>
    </Card>
  );
}
