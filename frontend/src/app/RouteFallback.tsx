import { Spinner } from '../components/ui/Spinner';

/** Shown while the first page's code is downloaded (routes are loaded on demand). */
export function RouteFallback() {
  return (
    <div className="grid min-h-dvh place-items-center">
      <div role="status" className="text-ink-muted flex items-center gap-3">
        <Spinner />
        <span>Loading…</span>
      </div>
    </div>
  );
}
