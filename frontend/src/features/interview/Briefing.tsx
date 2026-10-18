import { Link } from 'react-router';

import { useCatalogOptions } from '../../api/catalog';
import type { Session } from '../../api/types';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import {
  DIFFICULTY_LABEL,
  EXPERIENCE_LABEL,
  ROLE_LABEL,
  SECTION_LABEL,
  TYPE_LABEL,
} from '../../lib/labels';
import type { RoomCommands } from './useRoomCommands';

const RULES = [
  'The clock starts when you press Begin and runs on the server. Leaving this page does not pause it.',
  'Questions come one at a time. Follow-ups depend on what you answered.',
  'You will not see scores while the interview runs. The full report comes at the end.',
  'Your answers are rated by an AI model against a fixed rubric; the score is computed by the backend.',
];

/** Before the clock starts: what is about to happen, and the only button that starts it. */
export function Briefing({ session, commands }: { session: Session; commands: RoomCommands }) {
  const { data: options } = useCatalogOptions();
  const { configuration } = session;
  const sections =
    options?.interviewTypes.find((type) => type.value === configuration.interviewType)?.sections ??
    [];

  return (
    <div className="mx-auto w-full max-w-3xl space-y-8 px-4 py-10 sm:px-6 sm:py-16">
      <div>
        <p className="eyebrow">Ready when you are</p>
        <h1 className="text-headline mt-2">{TYPE_LABEL[configuration.interviewType]} interview</h1>
        <p className="text-ink-muted mt-2 text-lg">
          {ROLE_LABEL[configuration.role]} · {EXPERIENCE_LABEL[configuration.experienceLevel]} ·{' '}
          {DIFFICULTY_LABEL[configuration.difficulty]} · {configuration.durationMinutes} minutes
        </p>
      </div>

      <dl className="border-line bg-surface shadow-raised grid gap-6 rounded-2xl border p-6 sm:grid-cols-2">
        <div>
          <dt className="eyebrow">Technologies</dt>
          <dd className="mt-2 flex flex-wrap gap-2">
            {configuration.skills.map((skill) => (
              <Badge key={skill.slug} tone="brand">
                {skill.name}
              </Badge>
            ))}
          </dd>
        </div>
        <div>
          <dt className="eyebrow">Sections</dt>
          <dd className="mt-2">
            <ol className="space-y-1 text-sm font-semibold">
              {sections.map((section, index) => (
                <li key={section}>
                  {index + 1}. {SECTION_LABEL[section]}
                </li>
              ))}
            </ol>
          </dd>
        </div>
        {configuration.codingLanguage ? (
          <div>
            <dt className="eyebrow">Coding language</dt>
            <dd className="mt-2 text-sm font-semibold">{configuration.codingLanguage}</dd>
          </div>
        ) : null}
      </dl>

      <section aria-labelledby="rules-title">
        <h2 id="rules-title" className="text-lg font-semibold tracking-tight">
          Before you begin
        </h2>
        <ul className="mt-3 space-y-2.5">
          {RULES.map((rule) => (
            <li key={rule} className="flex gap-3 text-sm">
              <Icon name="check" className="text-success-solid mt-0.5 size-4.5 shrink-0" />
              <span>{rule}</span>
            </li>
          ))}
        </ul>
      </section>

      {commands.start.isError ? (
        <ErrorState
          error={commands.start.error}
          onRetry={() => {
            commands.start.mutate();
          }}
        />
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <Button
          size="lg"
          loading={commands.start.isPending}
          icon={<Icon name="play" className="size-4" />}
          onClick={() => {
            commands.start.mutate();
          }}
        >
          Begin interview
        </Button>
        <Link
          to="/dashboard"
          className="text-ink-muted text-sm font-semibold underline underline-offset-4"
        >
          Not now
        </Link>
      </div>
      <Alert tone="info">
        This is a practice interview. You can leave an unstarted interview here; it stays in your
        history until you start it.
      </Alert>
    </div>
  );
}
