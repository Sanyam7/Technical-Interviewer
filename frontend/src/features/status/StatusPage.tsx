import { usePageTitle } from '../../app/layout/usePageTitle';
import { HealthCard } from './HealthCard';

export function StatusPage() {
  usePageTitle('Service status');

  return (
    <div className="mx-auto w-full max-w-3xl space-y-8 px-4 py-12 sm:px-6">
      <section aria-labelledby="status-title">
        <h1 id="status-title" className="text-headline">
          Service status
        </h1>
        <p className="text-ink-muted mt-3 max-w-prose text-lg">
          Whether the backend and its database are responding right now.
        </p>
      </section>
      <HealthCard />
    </div>
  );
}
