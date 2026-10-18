import { ApiError } from '../api/http';

export interface ErrorDescription {
  title: string;
  message: string;
  traceId?: string;
  /** Whether trying the same thing again can help (network, timeout, 5xx, rate limit). */
  retryable: boolean;
}

/**
 * Turns anything thrown by the API layer into text for a person. The backend's `detail` is written for the client and is
 * safe to show; a failure without one gets a sentence by cause.
 */
export function describeError(error: unknown): ErrorDescription {
  if (!(error instanceof ApiError)) {
    return {
      title: 'Something went wrong',
      message: 'An unexpected error occurred. Try again.',
      retryable: true,
    };
  }

  const traceId = error.traceId;
  switch (true) {
    case error.code === 'NETWORK_ERROR' || error.code === 'TIMEOUT':
      return {
        title: error.code === 'TIMEOUT' ? 'The server took too long' : 'Cannot reach the server',
        message:
          error.code === 'TIMEOUT'
            ? 'The request was abandoned because the server did not answer in time. Your work is safe; try again.'
            : 'Check your connection and that the backend is running, then try again.',
        retryable: true,
      };
    case error.code === 'AI_PROVIDER_UNAVAILABLE':
      return {
        title: 'The AI interviewer is unavailable',
        message: 'The AI service did not answer. Nothing was lost: wait a moment and try again.',
        traceId,
        retryable: true,
      };
    case error.status === 429:
      return {
        title: 'Too many requests',
        message: error.detail ?? 'You are going too fast. Wait a moment and try again.',
        traceId,
        retryable: true,
      };
    case error.status === 404:
      return {
        title: 'Not found',
        message: 'This does not exist, or it belongs to another account.',
        traceId,
        retryable: false,
      };
    case error.status >= 500:
      return {
        title: 'The server had a problem',
        message: 'This is not caused by anything you did. Try again in a moment.',
        traceId,
        retryable: true,
      };
    default:
      return {
        title: error.title,
        message: error.detail ?? 'The request could not be completed.',
        traceId,
        retryable: false,
      };
  }
}

/**
 * The field errors of a 400 VALIDATION_FAILED, filed under the fields a form shows. The backend names a field by its path in
 * the request (`configuration.skills[1]`, `skills[0].skill`): an error belongs to the form field at the root of that path,
 * after `prefix`. Errors about anything the form has no field for are returned in `unshown` (as "field: message"), so a page
 * can still show them instead of failing silently.
 */
export function formErrors<F extends string>(
  error: unknown,
  shown: readonly F[],
  prefix = '',
): { fields: Partial<Record<F, string>>; unshown: string[] } {
  const fields: Partial<Record<F, string>> = {};
  const unshown: string[] = [];
  if (!(error instanceof ApiError)) {
    return { fields, unshown };
  }
  for (const { field, message } of error.errors) {
    const path = prefix !== '' && field.startsWith(prefix) ? field.slice(prefix.length) : field;
    const root = path.split(/[.[]/, 1)[0] ?? path;
    const known = shown.find((name) => name === root);
    if (known !== undefined && fields[known] === undefined) {
      fields[known] = message;
    } else if (known === undefined) {
      unshown.push(`${root}: ${message}`);
    }
  }
  return { fields, unshown };
}
