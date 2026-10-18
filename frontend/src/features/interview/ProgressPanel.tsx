import { useCatalogOptions } from '../../api/catalog';
import type { Session } from '../../api/types';
import { Card } from '../../components/ui/Card';
import { Icon } from '../../components/ui/Icon';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { cx } from '../../lib/cx';
import { SECTION_LABEL } from '../../lib/labels';

/** Where you are: the sections of this interview and how many of the section's planned questions are done. */
export function ProgressPanel({ session }: { session: Session }) {
  const { data: options } = useCatalogOptions();
  const { progress, configuration } = session;
  const sections =
    options?.interviewTypes.find((type) => type.value === configuration.interviewType)?.sections ??
    [];
  const current = progress.sectionIndex ?? 0;
  const planned = progress.plannedQuestionsInSection ?? 0;
  const answered = Math.min(progress.questionsAnswered, planned || progress.questionsAnswered);

  return (
    <Card title="Progress">
      {sections.length > 0 ? (
        <ol aria-label="Sections" className="space-y-3">
          {sections.map((section, index) => {
            const done = index < current;
            const active = index === current;
            return (
              <li
                key={section}
                aria-current={active ? 'step' : undefined}
                className={cx(
                  'flex items-center gap-3 text-sm',
                  active ? 'font-semibold' : done ? 'text-ink-muted' : 'text-ink-muted/80',
                )}
              >
                <span
                  className={cx(
                    'grid size-6 shrink-0 place-items-center rounded-full border text-xs font-bold',
                    done && 'border-success-solid bg-success-solid text-white',
                    active && 'border-brand bg-brand text-brand-ink',
                    !done && !active && 'border-line',
                  )}
                >
                  {done ? <Icon name="check" className="size-3.5" /> : index + 1}
                </span>
                {SECTION_LABEL[section]}
                {done ? <span className="sr-only"> (done)</span> : null}
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="text-sm">
          Section {current + 1} of {progress.sectionCount}
        </p>
      )}
      {planned > 0 ? (
        <div className="mt-5">
          <div className="flex items-baseline justify-between text-sm">
            <span className="text-ink-muted">Questions in this section</span>
            <span className="font-semibold tabular-nums">
              {answered} / {planned}
            </span>
          </div>
          <ProgressBar
            className="mt-2"
            label="Questions answered in this section"
            value={(answered / planned) * 100}
          />
        </div>
      ) : null}
    </Card>
  );
}
