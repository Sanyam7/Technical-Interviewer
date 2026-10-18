import { usePageTitle } from './layout/usePageTitle';
import { ButtonLink } from '../components/ui/Button';

export function NotFoundPage() {
  usePageTitle('Page not found');

  return (
    <section
      aria-labelledby="not-found-title"
      className="mx-auto w-full max-w-3xl px-4 py-20 sm:px-6"
    >
      <p className="eyebrow">Error 404</p>
      <h1 id="not-found-title" className="text-headline mt-2">
        Page not found
      </h1>
      <p className="text-ink-muted mt-3 max-w-prose">
        The page you are looking for does not exist or has moved.
      </p>
      <ButtonLink to="/" className="mt-6">
        Back to home
      </ButtonLink>
    </section>
  );
}
