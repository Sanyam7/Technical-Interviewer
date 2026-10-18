import { useState, type SyntheticEvent } from 'react';

import { useCatalogSkills } from '../../api/catalog';
import { useProfile, useUpdateProfile } from '../../api/profile';
import type { CatalogSkill, Profile, UpdateProfileRequest } from '../../api/types';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { ErrorState } from '../../components/ui/ErrorState';
import { SelectField, TextField } from '../../components/ui/Field';
import { Icon } from '../../components/ui/Icon';
import { PageHeader } from '../../components/ui/PageHeader';
import { Skeleton } from '../../components/ui/Skeleton';
import { formErrors } from '../../lib/errors';
import { useUser } from '../auth/authContext';
import { ResumeCard } from './ResumeCard';

const RATING_LABEL = [
  'Not rated',
  '1 · Beginner',
  '2 · Basic',
  '3 · Working knowledge',
  '4 · Strong',
  '5 · Expert',
];
const MAX_PROFILE_SKILLS = 20;

function toRequest(
  headline: string,
  jobTitle: string,
  years: string,
  ratings: Record<string, number>,
): UpdateProfileRequest {
  return {
    headline: headline.trim() || null,
    jobTitle: jobTitle.trim() || null,
    yearsExperience: years.trim() === '' ? null : Number(years),
    skills: Object.entries(ratings)
      .filter(([, rating]) => rating > 0)
      .map(([skill, selfRating]) => ({ skill, selfRating })),
  };
}

function ProfileForm({ profile, skills }: { profile: Profile; skills: CatalogSkill[] }) {
  const update = useUpdateProfile();
  const [headline, setHeadline] = useState(profile.headline ?? '');
  const [jobTitle, setJobTitle] = useState(profile.jobTitle ?? '');
  const [years, setYears] = useState(profile.yearsExperience?.toString() ?? '');
  const [ratings, setRatings] = useState<Record<string, number>>(() =>
    Object.fromEntries(profile.skills.map((skill) => [skill.skill, skill.selfRating])),
  );

  const { fields: errors, unshown } = formErrors(update.error, [
    'headline',
    'jobTitle',
    'yearsExperience',
    'skills',
  ]);
  const tooMany = Object.values(ratings).filter((rating) => rating > 0).length > MAX_PROFILE_SKILLS;

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    update.mutate(toRequest(headline, jobTitle, years, ratings));
  }

  return (
    <form onSubmit={onSubmit} className="space-y-6">
      <Card
        title="About you"
        description="Shown only to you. It helps keep your practice relevant."
      >
        <div className="grid gap-5 sm:grid-cols-2">
          <TextField
            label="Job title"
            maxLength={60}
            value={jobTitle}
            error={errors.jobTitle}
            fieldClassName="sm:col-span-1"
            onChange={(event) => {
              setJobTitle(event.target.value);
            }}
          />
          <TextField
            label="Years of experience"
            type="number"
            inputMode="numeric"
            min={0}
            max={60}
            value={years}
            error={errors.yearsExperience}
            onChange={(event) => {
              setYears(event.target.value);
            }}
          />
          <TextField
            label="Headline"
            maxLength={120}
            hint="One line about what you do, up to 120 characters."
            value={headline}
            error={errors.headline}
            fieldClassName="sm:col-span-2"
            onChange={(event) => {
              setHeadline(event.target.value);
            }}
          />
        </div>
      </Card>

      <Card
        title="Self-assessment"
        description="How well do you think you know each technology? Compare it with what your reports say."
      >
        <ul className="grid gap-x-8 gap-y-5 sm:grid-cols-2">
          {skills.map((skill) => (
            <li key={skill.slug}>
              <SelectField
                label={skill.name}
                value={ratings[skill.slug] ?? 0}
                onChange={(event) => {
                  setRatings((current) => ({
                    ...current,
                    [skill.slug]: Number(event.target.value),
                  }));
                }}
              >
                {RATING_LABEL.map((label, rating) => (
                  <option key={label} value={rating}>
                    {label}
                  </option>
                ))}
              </SelectField>
            </li>
          ))}
        </ul>
        {errors.skills ? (
          <p role="alert" className="text-danger-ink mt-3 text-sm font-medium">
            {errors.skills}
          </p>
        ) : null}
      </Card>

      {update.isError && Object.keys(errors).length === 0 && unshown.length === 0 ? (
        <ErrorState error={update.error} />
      ) : null}
      {unshown.length > 0 ? (
        <Alert tone="danger" title="The profile could not be saved">
          <ul className="list-disc pl-5">
            {unshown.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        </Alert>
      ) : null}
      {update.isSuccess ? <Alert tone="success" title="Profile saved" /> : null}

      <Button
        type="submit"
        size="lg"
        loading={update.isPending}
        disabled={tooMany}
        icon={<Icon name="check" className="size-4" />}
      >
        Save profile
      </Button>
    </form>
  );
}

export function ProfilePage() {
  usePageTitle('Profile');
  const user = useUser();
  const profile = useProfile();
  const skills = useCatalogSkills();

  return (
    <div className="space-y-8">
      <PageHeader eyebrow="Candidate profile" title={user.displayName} description={user.email} />

      {profile.isError || skills.isError ? (
        <ErrorState
          error={profile.error ?? skills.error}
          title="Your profile could not be loaded"
          onRetry={() => {
            void profile.refetch();
            void skills.refetch();
          }}
        />
      ) : !profile.data || !skills.data ? (
        <div role="status" aria-label="Loading your profile" className="space-y-4">
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      ) : (
        <ProfileForm profile={profile.data} skills={skills.data} />
      )}

      <ResumeCard />
    </div>
  );
}
