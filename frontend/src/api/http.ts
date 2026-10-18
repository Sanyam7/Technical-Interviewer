export interface ApiFieldError {
  field: string;
  message: string;
}

export interface ApiErrorInit {
  status: number;
  code: string;
  title: string;
  detail?: string;
  traceId?: string;
  errors?: ApiFieldError[];
  body?: unknown;
  cause?: unknown;
}

/** Error raised for every failed API call: RFC 9457 problem responses, bad responses and network failures. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string | undefined;
  readonly traceId: string | undefined;
  readonly errors: ApiFieldError[];
  /** Parsed JSON body of the failed response, if it had one. */
  readonly body: unknown;

  constructor(init: ApiErrorInit) {
    super(init.detail ?? init.title, init.cause === undefined ? undefined : { cause: init.cause });
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.title = init.title;
    this.detail = init.detail;
    this.traceId = init.traceId;
    this.errors = init.errors ?? [];
    this.body = init.body;
  }
}

/** A request that gets no answer within this time is abandoned, so a hung backend shows as unreachable. */
export const REQUEST_TIMEOUT_MS = 10_000;

/**
 * Requests that wait for the AI layer (an answer, a skip, the next question, finishing). The backend bounds every AI step by its
 * own time budget, so this only has to catch a dead connection, and it must not give up while the server is still working: a
 * turn is an evaluation plus the next question (with Gemini up to about three minutes, with a small local model up to about
 * eight), and finishing may retry evaluations before the report (API_DESIGN.md 6.4). A request abandoned too early reports a
 * failure for an answer the server did store.
 */
export const AI_REQUEST_TIMEOUT_MS = 600_000;

export interface ApiRequestInit extends RequestInit {
  /** Overrides {@link REQUEST_TIMEOUT_MS} for this call. */
  timeoutMs?: number;
}

const NETWORK_ERROR_CODE = 'NETWORK_ERROR';
const TIMEOUT_ERROR_CODE = 'TIMEOUT';
export const INVALID_RESPONSE_CODE = 'INVALID_RESPONSE';
const UNEXPECTED_RESPONSE_CODE = 'UNEXPECTED_RESPONSE';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function parseFieldErrors(value: unknown): ApiFieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry: unknown) => {
    if (!isRecord(entry)) {
      return [];
    }
    const field = optionalString(entry.field);
    const message = optionalString(entry.message);
    return field !== undefined && message !== undefined ? [{ field, message }] : [];
  });
}

function isJson(response: Response): boolean {
  const contentType = response.headers.get('Content-Type') ?? '';
  return contentType.includes('json');
}

async function readJson(response: Response): Promise<{ ok: true; value: unknown } | { ok: false }> {
  try {
    return { ok: true, value: (await response.json()) as unknown };
  } catch {
    return { ok: false };
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const parsed = isJson(response) ? await readJson(response) : { ok: false as const };
  const fallbackTitle = response.statusText || `HTTP ${response.status}`;

  if (parsed.ok && isRecord(parsed.value)) {
    const problem = parsed.value;
    return new ApiError({
      status: response.status,
      code: optionalString(problem.code) ?? UNEXPECTED_RESPONSE_CODE,
      title: optionalString(problem.title) ?? fallbackTitle,
      detail: optionalString(problem.detail),
      traceId: optionalString(problem.traceId) ?? response.headers.get('X-Request-Id') ?? undefined,
      errors: parseFieldErrors(problem.errors),
      body: parsed.value,
    });
  }

  return new ApiError({
    status: response.status,
    code: UNEXPECTED_RESPONSE_CODE,
    title: fallbackTitle,
    traceId: response.headers.get('X-Request-Id') ?? undefined,
    body: parsed.ok ? parsed.value : undefined,
  });
}

/** An unset VITE_API_BASE_URL means same-origin, exactly like an empty one. */
function resolveUrl(path: string): string {
  const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');
  return `${baseUrl}/${path.replace(/^\/+/, '')}`;
}

/** crypto.randomUUID exists only in secure contexts; getRandomValues is available everywhere. */
function requestId(): string {
  const webCrypto: Partial<Crypto> = crypto;
  if (typeof webCrypto.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-');
}

function isDomException(error: unknown, name: string): boolean {
  return error instanceof DOMException && error.name === name;
}

/**
 * Calls the backend and returns the parsed JSON body.
 * Failures (including a request that exceeds its timeout, {@link REQUEST_TIMEOUT_MS} unless set) are thrown as {@link ApiError};
 * a request aborted by the caller rethrows the native AbortError.
 */
export async function apiFetch<T>(path: string, request: ApiRequestInit = {}): Promise<T> {
  const { timeoutMs = REQUEST_TIMEOUT_MS, ...init } = request;
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Request-Id', requestId());
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const url = resolveUrl(path);
  const timeout = AbortSignal.timeout(timeoutMs);
  const signal = init.signal ? AbortSignal.any([init.signal, timeout]) : timeout;

  let response: Response;
  try {
    response = await fetch(url, { ...init, headers, signal });
  } catch (error) {
    if (isDomException(error, 'TimeoutError')) {
      throw new ApiError({
        status: 0,
        code: TIMEOUT_ERROR_CODE,
        title: 'Request timed out',
        detail: 'The backend did not answer in time.',
        cause: error,
      });
    }
    if (isDomException(error, 'AbortError') || init.signal?.aborted === true) {
      throw error;
    }
    throw new ApiError({
      status: 0,
      code: NETWORK_ERROR_CODE,
      title: 'Network error',
      detail: 'The backend could not be reached.',
      cause: error,
    });
  }

  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }

  const parsed = await readJson(response);
  if (!parsed.ok) {
    throw new ApiError({
      status: response.status,
      code: INVALID_RESPONSE_CODE,
      title: 'Invalid response',
      detail: 'The backend returned a body that is not valid JSON.',
    });
  }
  return parsed.value as T;
}
