import { describeError } from '../../lib/errors';
import { Alert } from './Alert';
import { Button } from './Button';

interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
  /** Replaces the generic title, for example "The report could not be loaded". */
  title?: string;
  className?: string;
}

/** What the person sees when a request failed: what happened, whether trying again can help, and the trace id for support. */
export function ErrorState({
  error,
  onRetry,
  retrying = false,
  title,
  className,
}: ErrorStateProps) {
  const description = describeError(error);
  return (
    <Alert
      tone="danger"
      title={title ?? description.title}
      className={className}
      action={
        onRetry && description.retryable ? (
          <Button variant="secondary" size="sm" onClick={onRetry} loading={retrying}>
            Try again
          </Button>
        ) : undefined
      }
    >
      <p>{title ? `${description.title}. ${description.message}` : description.message}</p>
      {description.traceId ? (
        <p className="mt-1 font-mono text-xs opacity-80">Reference: {description.traceId}</p>
      ) : null}
    </Alert>
  );
}
