import type { ReactNode } from 'react';

import { Icon, type IconName } from '../../components/ui/Icon';

interface AuthFrameProps {
  title: string;
  description: string;
  children: ReactNode;
  footer: ReactNode;
}

const POINTS: { icon: IconName; title: string; text: string }[] = [
  {
    icon: 'target',
    title: 'Questions that adapt',
    text: 'Difficulty follows your answers, and weak topics come back until they are solid.',
  },
  {
    icon: 'shield',
    title: 'A fixed rubric, not a vibe',
    text: 'The AI rates each criterion; the score itself is computed by the backend, the same way every time.',
  },
  {
    icon: 'book',
    title: 'Grounded in real material',
    text: 'Questions and your study plan cite a curated knowledge base.',
  },
];

/** Two columns: the form, and what the product does. The second column is only decoration for the form's task, so it is hidden below `lg`. */
export function AuthFrame({ title, description, children, footer }: AuthFrameProps) {
  return (
    <div className="mx-auto grid w-full max-w-6xl gap-10 px-4 py-10 sm:px-6 lg:grid-cols-2 lg:gap-16 lg:py-20">
      <div className="mx-auto w-full max-w-md lg:mx-0">
        <h1 className="text-headline">{title}</h1>
        <p className="text-ink-muted mt-2">{description}</p>
        <div className="mt-8">{children}</div>
        <p className="text-ink-muted mt-6 text-sm">{footer}</p>
      </div>
      <aside
        aria-label="About the product"
        className="bg-hero border-line bg-surface relative hidden overflow-hidden rounded-3xl border p-10 lg:block"
      >
        <div className="bg-grid absolute inset-0 opacity-60" aria-hidden="true" />
        <div className="relative space-y-8">
          <p className="eyebrow">Practice like it counts</p>
          <ul className="space-y-7">
            {POINTS.map((point) => (
              <li key={point.title} className="flex gap-4">
                <span className="bg-brand-soft text-brand-soft-ink grid size-10 shrink-0 place-items-center rounded-xl">
                  <Icon name={point.icon} />
                </span>
                <div>
                  <h2 className="font-semibold">{point.title}</h2>
                  <p className="text-ink-muted mt-1 text-sm">{point.text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </aside>
    </div>
  );
}
