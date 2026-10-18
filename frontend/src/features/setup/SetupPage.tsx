import { useMutation } from '@tanstack/react-query';
import { useId, useState, type SyntheticEvent } from 'react';
import { Link, useNavigate } from 'react-router';

import { useCatalogOptions, useCatalogSkills } from '../../api/catalog';
import { ApiError } from '../../api/http';
import { createInterview, useRecentConfigurations } from '../../api/interviews';
import { useResume } from '../../api/resume';
import type {
  ConfigurationRequest,
  Difficulty,
  ExperienceLevel,
  InterviewConfiguration,
  InterviewStrategy,
  InterviewType,
  Role,
  StageMix,
} from '../../api/types';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { ChipGroup, ChoiceGroup } from '../../components/ui/Choice';
import { ErrorState } from '../../components/ui/ErrorState';
import { SelectField } from '../../components/ui/Field';
import { Icon } from '../../components/ui/Icon';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/Skeleton';
import { describeError, formErrors } from '../../lib/errors';
import {
  DIFFICULTY_LABEL,
  EXPERIENCE_LABEL,
  ROLE_HINT,
  ROLE_LABEL,
  SECTION_LABEL,
  STRATEGY_LABEL,
  TYPE_HINT,
  TYPE_LABEL,
} from '../../lib/labels';
import { StrategyPicker } from './StrategyPicker';
import { DEFAULT_CUSTOM_MIX, describeMix, mixTotal } from './stageMix';

const MAX_SKILLS = 6;

interface Draft {
  role: Role;
  experienceLevel: ExperienceLevel;
  skills: string[];
  difficulty: Difficulty;
  durationMinutes: number;
  interviewType: InterviewType;
  codingLanguage: string;
  useResume: boolean;
  strategy: InterviewStrategy;
  stageMix: StageMix;
}

const DEFAULT_DRAFT: Draft = {
  role: 'BACKEND',
  experienceLevel: 'MID',
  skills: [],
  difficulty: 'MEDIUM',
  durationMinutes: 30,
  interviewType: 'TECHNICAL',
  codingLanguage: '',
  useResume: false,
  strategy: 'FUNDAMENTALS_FIRST',
  stageMix: DEFAULT_CUSTOM_MIX,
};

function needsLanguage(type: InterviewType): boolean {
  return type === 'CODING' || type === 'FULL_LOOP';
}

/** Only interviews with technical questions are divided into fundamentals, practical and project questions. */
function asksQuestions(type: InterviewType): boolean {
  return type === 'TECHNICAL' || type === 'FULL_LOOP';
}

function fromConfiguration(configuration: InterviewConfiguration): Draft {
  return {
    role: configuration.role,
    experienceLevel: configuration.experienceLevel,
    skills: configuration.skills.map((skill) => skill.slug),
    difficulty: configuration.difficulty,
    durationMinutes: configuration.durationMinutes,
    interviewType: configuration.interviewType,
    codingLanguage: configuration.codingLanguage ?? '',
    useResume: configuration.useResume,
    strategy: configuration.strategy,
    stageMix: configuration.strategy === 'CUSTOM' ? configuration.stageMix : DEFAULT_CUSTOM_MIX,
  };
}

function toRequest(draft: Draft): ConfigurationRequest {
  const strategy = asksQuestions(draft.interviewType) ? draft.strategy : 'FUNDAMENTALS_FIRST';
  return {
    role: draft.role,
    experienceLevel: draft.experienceLevel,
    skills: draft.skills,
    difficulty: draft.difficulty,
    durationMinutes: draft.durationMinutes as ConfigurationRequest['durationMinutes'],
    interviewType: draft.interviewType,
    codingLanguage: needsLanguage(draft.interviewType) ? draft.codingLanguage : null,
    useResume: draft.useResume || strategy === 'RESUME_FOCUSED',
    strategy,
    stageMix: strategy === 'CUSTOM' ? draft.stageMix : null,
  };
}

function RecentSetups({ onPick }: { onPick: (configuration: InterviewConfiguration) => void }) {
  const { data } = useRecentConfigurations();
  const items = data?.items ?? [];
  if (items.length === 0) {
    return null;
  }
  return (
    <Card
      title="Reuse a recent setup"
      description="Fills in the form; you can still change anything."
    >
      <ul className="flex flex-wrap gap-2.5">
        {items.map((configuration) => (
          <li key={configuration.id}>
            <button
              type="button"
              onClick={() => {
                onPick(configuration);
              }}
              className="border-line bg-surface hover:border-control-border min-h-11 rounded-xl border px-4 py-2 text-left text-sm"
            >
              <span className="block font-semibold">
                {TYPE_LABEL[configuration.interviewType]} · {configuration.durationMinutes} min
              </span>
              <span className="text-ink-muted block text-xs">
                {configuration.skills.map((skill) => skill.name).join(', ')}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

export function SetupPage() {
  usePageTitle('New interview');
  const navigate = useNavigate();
  const options = useCatalogOptions();
  const skills = useCatalogSkills();
  const resume = useResume();
  const [draft, setDraft] = useState<Draft>(DEFAULT_DRAFT);
  const [triedToSubmit, setTriedToSubmit] = useState(false);

  const create = useMutation({
    mutationFn: (request: ConfigurationRequest) => createInterview({ configuration: request }),
    onSuccess: (created) => navigate(`/interviews/${created.id}/live`),
  });

  if (options.isError || skills.isError) {
    return (
      <ErrorState
        error={options.error ?? skills.error}
        title="The interview options could not be loaded"
        onRetry={() => {
          void options.refetch();
          void skills.refetch();
        }}
      />
    );
  }
  if (!options.data || !skills.data) {
    return (
      <div role="status" aria-label="Loading the interview options" className="space-y-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-48 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    );
  }

  const catalog = options.data;
  const catalogSkills = skills.data;
  const language =
    draft.codingLanguage !== '' ? draft.codingLanguage : (catalog.codingLanguages[0] ?? '');
  const sections =
    catalog.interviewTypes.find((type) => type.value === draft.interviewType)?.sections ?? [];
  const { fields: serverErrors, unshown } = formErrors(
    create.error,
    ['skills', 'codingLanguage', 'durationMinutes', 'useResume', 'strategy', 'stageMix'],
    'configuration.',
  );
  const dailyLimit =
    create.error instanceof ApiError && create.error.code === 'DAILY_INTERVIEW_LIMIT';
  const noSkills = draft.skills.length === 0;
  const hasResume = Boolean(resume.data);
  const staged = asksQuestions(draft.interviewType);
  const resumeFocused = staged && draft.strategy === 'RESUME_FOCUSED';
  const badMix = staged && draft.strategy === 'CUSTOM' && mixTotal(draft.stageMix) !== 100;

  function update(patch: Partial<Draft>) {
    setDraft((current) => ({ ...current, ...patch }));
  }

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    setTriedToSubmit(true);
    if (noSkills || badMix || create.isPending) {
      return;
    }
    create.mutate(
      toRequest({ ...draft, codingLanguage: language, useResume: draft.useResume && hasResume }),
    );
  }

  return (
    <div className="space-y-8">
      <PageHeader
        eyebrow="New interview"
        title="Set up your interview"
        description="Choose what you want to be asked about. The clock does not start until you press Begin on the next screen."
      />

      <form
        onSubmit={onSubmit}
        noValidate
        className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_20rem]"
        aria-label="Interview setup"
      >
        <div className="space-y-6">
          <RecentSetups
            onPick={(configuration) => {
              setDraft(fromConfiguration(configuration));
            }}
          />

          <Card title="About you">
            <div className="space-y-6">
              <ChoiceGroup<Role>
                legend="Role"
                value={draft.role}
                onChange={(role) => {
                  update({ role });
                }}
                options={catalog.roles.map((role) => ({
                  value: role,
                  label: ROLE_LABEL[role],
                  hint: ROLE_HINT[role],
                }))}
                columns="grid-cols-1 sm:grid-cols-3"
              />
              <ChoiceGroup<ExperienceLevel>
                legend="Experience level"
                value={draft.experienceLevel}
                onChange={(experienceLevel) => {
                  update({ experienceLevel });
                }}
                options={catalog.experienceLevels.map((level) => ({
                  value: level,
                  label: EXPERIENCE_LABEL[level],
                }))}
                columns="grid-cols-2 sm:grid-cols-5"
              />
            </div>
          </Card>

          <Card
            title="Technologies"
            description={`Pick up to ${MAX_SKILLS}. Questions are drawn from these.`}
          >
            <ChipGroup
              legend="Technologies"
              hint={`${draft.skills.length} of ${MAX_SKILLS} selected`}
              options={catalogSkills.map((skill) => ({ value: skill.slug, label: skill.name }))}
              selected={draft.skills}
              max={MAX_SKILLS}
              onChange={(selected) => {
                update({ skills: selected });
              }}
              error={
                serverErrors.skills ??
                (triedToSubmit && noSkills ? 'Choose at least one technology.' : undefined)
              }
            />
          </Card>

          <Card title="Format">
            <div className="space-y-6">
              <ChoiceGroup<InterviewType>
                legend="Interview type"
                value={draft.interviewType}
                onChange={(interviewType) => {
                  update({ interviewType });
                }}
                options={catalog.interviewTypes.map((type) => ({
                  value: type.value,
                  label: TYPE_LABEL[type.value],
                  hint: TYPE_HINT[type.value],
                }))}
                columns="grid-cols-1 sm:grid-cols-2"
              />
              {needsLanguage(draft.interviewType) ? (
                <SelectField
                  label="Coding language"
                  hint={
                    catalog.executableLanguages.includes(language)
                      ? 'Your solution runs against hidden tests in a sandbox; the tests decide its correctness.'
                      : 'Not run here: the AI interviewer reviews your code, and its correctness counts as unverified.'
                  }
                  value={language}
                  error={serverErrors.codingLanguage}
                  onChange={(event) => {
                    update({ codingLanguage: event.target.value });
                  }}
                  fieldClassName="max-w-xs"
                >
                  {catalog.codingLanguages.map((code) => (
                    <option key={code} value={code}>
                      {catalog.executableLanguages.includes(code)
                        ? `${code} · tests run`
                        : `${code} · AI review only`}
                    </option>
                  ))}
                </SelectField>
              ) : null}
              {staged ? (
                <StrategyPicker
                  strategies={catalog.strategies}
                  value={draft.strategy}
                  mix={draft.stageMix}
                  hasResume={hasResume}
                  error={serverErrors.strategy ?? serverErrors.stageMix}
                  showMixErrors={triedToSubmit}
                  onChange={(strategy) => {
                    update({ strategy });
                  }}
                  onMixChange={(stageMix) => {
                    update({ stageMix });
                  }}
                />
              ) : null}
              <ResumeOption
                checked={(draft.useResume || resumeFocused) && hasResume}
                hasResume={hasResume}
                loading={resume.isPending}
                locked={resumeFocused}
                error={serverErrors.useResume}
                onChange={(useResume) => {
                  update({ useResume });
                }}
              />
              <div className="grid gap-6 sm:grid-cols-2">
                <ChoiceGroup<Difficulty>
                  legend="Difficulty"
                  value={draft.difficulty}
                  onChange={(difficulty) => {
                    update({ difficulty });
                  }}
                  options={catalog.difficulties.map((difficulty) => ({
                    value: difficulty,
                    label: DIFFICULTY_LABEL[difficulty],
                  }))}
                  columns="grid-cols-3"
                />
                <SelectField
                  label="Duration"
                  value={draft.durationMinutes}
                  error={serverErrors.durationMinutes}
                  onChange={(event) => {
                    update({ durationMinutes: Number(event.target.value) });
                  }}
                >
                  {catalog.durationsMinutes.map((minutes) => (
                    <option key={minutes} value={minutes}>
                      {minutes} minutes
                    </option>
                  ))}
                </SelectField>
              </div>
            </div>
          </Card>
        </div>

        <aside
          aria-label="Summary"
          className="border-line bg-surface shadow-raised space-y-5 rounded-2xl border p-5 xl:sticky xl:top-6"
        >
          <h2 className="text-lg font-semibold tracking-tight">Summary</h2>
          <dl className="space-y-3 text-sm">
            <SummaryRow label="Role" value={ROLE_LABEL[draft.role]} />
            <SummaryRow label="Level" value={EXPERIENCE_LABEL[draft.experienceLevel]} />
            <SummaryRow label="Type" value={TYPE_LABEL[draft.interviewType]} />
            <SummaryRow label="Difficulty" value={DIFFICULTY_LABEL[draft.difficulty]} />
            <SummaryRow label="Time" value={`${draft.durationMinutes} min`} />
            {needsLanguage(draft.interviewType) ? (
              <SummaryRow label="Language" value={language} />
            ) : null}
            {staged ? (
              <SummaryRow
                label="Approach"
                value={
                  draft.strategy === 'CUSTOM'
                    ? describeMix(draft.stageMix)
                    : STRATEGY_LABEL[draft.strategy]
                }
              />
            ) : null}
            <SummaryRow
              label="Résumé"
              value={(draft.useResume || resumeFocused) && hasResume ? 'Personalised' : 'Not used'}
            />
          </dl>
          <div>
            <p className="text-ink-muted text-sm">Sections</p>
            <ul className="mt-2 flex flex-wrap gap-2">
              {sections.map((section) => (
                <li key={section}>
                  <Badge tone="brand">{SECTION_LABEL[section]}</Badge>
                </li>
              ))}
            </ul>
          </div>
          <div>
            <p className="text-ink-muted text-sm">Technologies</p>
            {noSkills ? (
              <p className="text-warning-ink mt-1 text-sm font-medium">Choose at least one.</p>
            ) : (
              <p className="mt-1 text-sm font-semibold">
                {draft.skills
                  .map((slug) => catalogSkills.find((skill) => skill.slug === slug)?.name ?? slug)
                  .join(', ')}
              </p>
            )}
          </div>

          {create.isError ? (
            dailyLimit ? (
              <Alert tone="warning" title="Daily limit reached">
                {create.error instanceof ApiError && create.error.detail
                  ? create.error.detail
                  : 'You have started as many interviews as a day allows.'}{' '}
                Come back tomorrow, or continue one you have already created.
              </Alert>
            ) : (
              <Alert tone="danger" title={describeError(create.error).title}>
                {describeError(create.error).message}
                {unshown.length > 0 ? (
                  <ul className="mt-2 list-disc pl-5">
                    {unshown.map((problem) => (
                      <li key={problem}>{problem}</li>
                    ))}
                  </ul>
                ) : null}
              </Alert>
            )
          ) : null}

          <Button
            type="submit"
            size="lg"
            className="w-full"
            loading={create.isPending}
            icon={<Icon name="arrow-right" className="order-last size-4" />}
          >
            Create interview
          </Button>
        </aside>
      </form>
    </div>
  );
}

interface ResumeOptionProps {
  checked: boolean;
  hasResume: boolean;
  loading: boolean;
  /** The approach needs the résumé, so it cannot be switched off. */
  locked: boolean;
  error: string | undefined;
  onChange: (checked: boolean) => void;
}

/** Personalisation from the résumé: offered only once a résumé is uploaded, with a way to upload one. */
function ResumeOption({ checked, hasResume, loading, locked, error, onChange }: ResumeOptionProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <div>
      <div className="flex items-start gap-3">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={!hasResume || loading || locked}
          aria-describedby={hintId}
          className="accent-brand mt-1 size-4.5"
          onChange={(event) => {
            onChange(event.target.checked);
          }}
        />
        <div>
          <label htmlFor={id} className="text-sm font-semibold">
            Personalise with my résumé
          </label>
          <p id={hintId} className="text-ink-muted mt-0.5 text-sm">
            {locked ? (
              'A résumé-focused interview always uses your résumé.'
            ) : hasResume ? (
              'The questions about your own projects are about the work your résumé describes, and only about that. They come after the fundamentals, so a short interview may not reach them.'
            ) : (
              <>
                Upload a résumé on your{' '}
                <Link
                  to="/profile"
                  className="text-brand font-semibold underline underline-offset-4"
                >
                  profile
                </Link>{' '}
                to get questions about your own projects.
              </>
            )}
          </p>
        </div>
      </div>
      {error ? (
        <p role="alert" className="text-danger-ink mt-1.5 text-sm font-medium">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-muted">{label}</dt>
      <dd className="text-right font-semibold">{value}</dd>
    </div>
  );
}
