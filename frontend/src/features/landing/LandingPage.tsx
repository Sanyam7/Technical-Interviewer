import { usePageTitle } from '../../app/layout/usePageTitle';
import { useCatalogSkills } from '../../api/catalog';
import { Badge } from '../../components/ui/Badge';
import { ButtonLink } from '../../components/ui/Button';
import { Icon, type IconName } from '../../components/ui/Icon';
import { useAuth } from '../auth/authContext';

const CRITERIA = [
  'Correctness',
  'Completeness',
  'Depth',
  'Practical understanding',
  'Reasoning',
  'Communication',
];

const STEPS = [
  {
    title: 'Set up the interview',
    text: 'Pick a role, your technologies, a difficulty and how long you have. Choose Q&A, coding, system design or the full loop.',
  },
  {
    title: 'Answer in a real session',
    text: 'One question at a time, on a server-side clock. Follow-ups probe exactly what you just said.',
  },
  {
    title: 'Read your report',
    text: 'Scores per skill, the concepts you missed, the misconceptions to fix, and a study plan with sources.',
  },
];

const FEATURES: { icon: IconName; title: string; text: string }[] = [
  {
    icon: 'target',
    title: 'Adaptive difficulty',
    text: 'The engine raises or lowers difficulty from your answers and revisits weak topics. The model words questions; it never decides what comes next.',
  },
  {
    icon: 'shield',
    title: 'Scores the backend computes',
    text: 'The AI rates each criterion against a rubric. Weights, caps and the final score are deterministic server code, tuned per skill.',
  },
  {
    icon: 'book',
    title: 'Grounded in a knowledge base',
    text: 'Questions and study plans cite curated reference material instead of whatever a model remembers.',
  },
  {
    icon: 'code',
    title: 'A real coding workspace',
    text: 'A syntax-highlighted editor with the problem, constraints and examples beside it, in the language you choose.',
  },
  {
    icon: 'layers',
    title: 'System design practice',
    text: 'Requirements, data model, scalability and trade-offs are rated separately, so you see which part of a design is thin.',
  },
  {
    icon: 'gap',
    title: 'Knowledge-gap report',
    text: 'Missed concepts, misconceptions with corrections, weak topics, and mistakes you repeat across questions.',
  },
];

function SkillList() {
  const { data } = useCatalogSkills();
  if (!data || data.length === 0) {
    return null;
  }
  return (
    <section aria-labelledby="skills-title" className="mx-auto w-full max-w-6xl px-4 py-14 sm:px-6">
      <p className="eyebrow">Technologies</p>
      <h2 id="skills-title" className="text-headline mt-2">
        Interview on the stack you use
      </h2>
      <ul className="mt-6 flex flex-wrap gap-2.5">
        {data.map((skill) => (
          <li key={skill.slug}>
            <span className="border-line bg-surface inline-flex min-h-10 items-center rounded-full border px-4 text-sm font-semibold shadow-sm">
              {skill.name}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function ProductPreview() {
  return (
    <div
      aria-hidden="true"
      className="border-line bg-surface shadow-raised relative mx-auto w-full max-w-lg rounded-3xl border p-5 sm:p-6"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Badge tone="brand">Java · Concurrency</Badge>
          <Badge>Follow-up</Badge>
        </div>
        <span className="text-ink-muted inline-flex items-center gap-1.5 text-xs font-semibold tabular-nums">
          <Icon name="clock" className="size-4" />
          24:10
        </span>
      </div>
      <p className="mt-5 text-lg leading-snug font-semibold tracking-tight text-pretty">
        You said{' '}
        <code className="bg-surface-muted rounded px-1 font-mono text-[0.9em]">volatile</code> makes
        the counter safe. What does{' '}
        <code className="bg-surface-muted rounded px-1 font-mono text-[0.9em]">count++</code>{' '}
        compile to, and why does that matter?
      </p>
      <div className="border-control-border bg-page mt-5 min-h-24 rounded-xl border p-3 text-sm">
        <span className="text-ink-muted">It is a read, an add and a write, so two threads can</span>
        <span className="bg-brand ml-0.5 inline-block h-4 w-0.5 animate-pulse align-middle" />
      </div>
      <p className="eyebrow mt-6">Assessed on</p>
      <ul className="mt-2 flex flex-wrap gap-2">
        {CRITERIA.map((criterion) => (
          <li
            key={criterion}
            className="bg-surface-muted text-ink-muted rounded-full px-3 py-1 text-xs font-semibold"
          >
            {criterion}
          </li>
        ))}
      </ul>
    </div>
  );
}

export function LandingPage() {
  usePageTitle(null);
  const { state } = useAuth();
  const signedIn = state.status === 'authenticated';

  return (
    <>
      <section className="bg-hero relative overflow-hidden">
        <div className="bg-grid absolute inset-0 opacity-50" aria-hidden="true" />
        <div className="relative mx-auto grid w-full max-w-6xl items-center gap-12 px-4 py-16 sm:px-6 lg:grid-cols-[1.1fr_1fr] lg:py-24">
          <div>
            <Badge tone="brand">
              <Icon name="sparkle" className="size-3.5" /> AI technical interview practice
            </Badge>
            <h1 className="text-display mt-5 text-balance">
              Interview practice that shows its work.
            </h1>
            <p className="text-ink-muted mt-5 max-w-xl text-lg text-pretty">
              Answer real interview questions, get follow-ups on what you actually said, and finish
              with a report that names the concepts you missed and the evidence behind every score.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <ButtonLink
                to={signedIn ? '/interviews/new' : '/register'}
                size="lg"
                icon={<Icon name="arrow-right" className="order-last size-4" />}
              >
                {signedIn ? 'Start an interview' : 'Start practising'}
              </ButtonLink>
              {signedIn ? null : (
                <ButtonLink to="/login" variant="secondary" size="lg">
                  Sign in
                </ButtonLink>
              )}
            </div>
          </div>
          <ProductPreview />
        </div>
      </section>

      <section
        aria-labelledby="steps-title"
        className="mx-auto w-full max-w-6xl px-4 py-14 sm:px-6"
      >
        <p className="eyebrow">How it works</p>
        <h2 id="steps-title" className="text-headline mt-2">
          From setup to study plan in three steps
        </h2>
        <ol className="mt-8 grid gap-5 md:grid-cols-3">
          {STEPS.map((step, index) => (
            <li
              key={step.title}
              className="border-line bg-surface shadow-raised rounded-2xl border p-6"
            >
              <span className="bg-brand text-brand-ink grid size-9 place-items-center rounded-full text-sm font-bold tabular-nums">
                {index + 1}
              </span>
              <h3 className="mt-4 text-lg font-semibold tracking-tight">{step.title}</h3>
              <p className="text-ink-muted mt-2 text-sm">{step.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <SkillList />

      <section
        aria-labelledby="features-title"
        className="mx-auto w-full max-w-6xl px-4 py-14 sm:px-6"
      >
        <p className="eyebrow">What is inside</p>
        <h2 id="features-title" className="text-headline mt-2 max-w-2xl text-balance">
          Built so the score means something
        </h2>
        <ul className="mt-8 grid gap-x-8 gap-y-10 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((feature) => (
            <li key={feature.title}>
              <span className="bg-brand-soft text-brand-soft-ink grid size-11 place-items-center rounded-xl">
                <Icon name={feature.icon} className="size-5" />
              </span>
              <h3 className="mt-4 font-semibold tracking-tight">{feature.title}</h3>
              <p className="text-ink-muted mt-1.5 text-sm">{feature.text}</p>
            </li>
          ))}
        </ul>
      </section>

      <section className="mx-auto w-full max-w-6xl px-4 py-14 sm:px-6">
        <div className="border-line bg-surface shadow-raised relative overflow-hidden rounded-3xl border p-8 sm:p-12">
          <div className="bg-hero absolute inset-0" aria-hidden="true" />
          <div className="relative max-w-2xl">
            <h2 className="text-headline text-balance">
              Know where you stand before the real one.
            </h2>
            <p className="text-ink-muted mt-3">
              Practice interviews are rated by an AI model. They are a practice signal, not a hiring
              decision, and the report says how confident each result is.
            </p>
            <ButtonLink to={signedIn ? '/dashboard' : '/register'} size="lg" className="mt-7">
              {signedIn ? 'Go to your dashboard' : 'Create your account'}
            </ButtonLink>
          </div>
        </div>
      </section>
    </>
  );
}
