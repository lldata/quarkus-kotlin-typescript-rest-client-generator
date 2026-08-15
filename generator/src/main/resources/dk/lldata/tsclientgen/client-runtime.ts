// Written by quarkus-kotlin-typescript-rest-client-generator. Safe to edit, and
// safe to replace with your own module: the generated client imports
// `ApiConfig`, `RequestOptions` and `apiRequest` from here and knows nothing
// else about how a request is made.
//
// Replace it when the transport carries project policy — an auth refresh, a
// proxy hop, retries, tracing headers. Keep the three exported names and the
// generated client needs no regeneration.

/** One HTTP call as the generated client describes it, path interpolated. */
export type ApiRequest = {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  path: string;
  query?: Record<string, string | number | boolean | null | undefined>;
  body?: unknown;
};

/**
 * What a client instance carries for every call it makes.
 *
 * Held in a closure by `createApi`, never at module level: a server serving
 * many users from one process would otherwise share one caller's token with the
 * next. A browser passes nothing — `credentials` sends the session cookie and
 * no call site ever sees this.
 */
export type ApiConfig = {
  /** Prefixed to every path. Empty for same-origin — the browser case. */
  baseUrl?: string;
  /** Sent as `Authorization: Bearer …`. */
  token?: string;
  /** Default `"same-origin"`; `"include"` for a cross-origin cookie session. */
  credentials?: RequestCredentials;
  /** Headers every call from this instance sends. */
  headers?: Record<string, string>;
  /** Injectable for tests and for runtimes where `fetch` is not global. */
  fetch?: typeof globalThis.fetch;
};

/**
 * Per-call transport concerns, kept separate from the endpoint's own arguments:
 * a query parameter could legitimately be named `signal`, and merging the two
 * would make that unrepresentable.
 *
 * Every generated call takes these, because real call sites need them — a
 * typeahead that cannot abort is a bug, and an upload that cannot raise its
 * timeout fails on a slow connection.
 */
export type RequestOptions = {
  signal?: AbortSignal;
  timeoutMs?: number;
  headers?: Record<string, string>;
  cache?: RequestCache;
};

/**
 * A non-2xx answer. The parsed body is kept, because that is where the server
 * said why.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly url: string;
  readonly body: unknown;

  constructor(status: number, url: string, body: unknown) {
    super(`${status} from ${url}`);
    this.name = "ApiError";
    this.status = status;
    this.url = url;
    this.body = body;
  }

  static async from(response: Response): Promise<ApiError> {
    const text = await response.text().catch(() => "");
    let body: unknown = text;
    try {
      body = text.length > 0 ? JSON.parse(text) : undefined;
    } catch {
      // Not JSON — an HTML error page, or a proxy's plain text. Keep it as it
      // came.
    }
    return new ApiError(response.status, response.url, body);
  }
}

function withQuery(path: string, query: ApiRequest["query"]): string {
  if (query == null) return path;
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    // An absent optional query param is absent from the URL, not
    // `?lang=undefined`.
    if (value == null) continue;
    search.set(key, String(value));
  }
  const rendered = search.toString();
  return rendered.length > 0 ? `${path}?${rendered}` : path;
}

/**
 * A caller's own signal and a timeout are both reasons to abort, so the call
 * honours either.
 */
function requestSignal(options?: RequestOptions): AbortSignal | undefined {
  const timeout =
    options?.timeoutMs != null
      ? AbortSignal.timeout(options.timeoutMs)
      : undefined;
  if (timeout == null) return options?.signal;
  if (options?.signal == null) return timeout;
  return AbortSignal.any([options.signal, timeout]);
}

/**
 * `config` is separate from `options` rather than a field inside it, so a token
 * can only come from `createApi`. Folding it into `RequestOptions` gives every
 * call site a second way in that the generated code then has to override, and
 * an override that loses is indistinguishable from one that works until someone
 * reads the request.
 */
export async function apiRequest<T>(
  request: ApiRequest,
  config: ApiConfig,
  options?: RequestOptions,
): Promise<T> {
  const path = withQuery(request.path, request.query);
  const url = `${config.baseUrl ?? ""}${path}`;
  const hasBody = request.body !== undefined;
  const call = config.fetch ?? globalThis.fetch;

  const response = await call(url, {
    method: request.method,
    credentials: config.credentials ?? "same-origin",
    headers: {
      ...config.headers,
      ...options?.headers,
      ...(hasBody ? { "content-type": "application/json" } : {}),
      ...(config.token != null
        ? { authorization: `Bearer ${config.token}` }
        : {}),
    },
    ...(hasBody ? { body: JSON.stringify(request.body) } : {}),
    ...(options?.cache != null ? { cache: options.cache } : {}),
    signal: requestSignal(options),
  });

  if (!response.ok) throw await ApiError.from(response);
  // A bodyless endpoint (`RestResponse<Void>`, emitted as `Promise<void>`)
  // answers with no content, and `response.json()` throws on an empty body.
  // Read once and parse only what is there, so the status alone can be the
  // whole answer.
  const text = await response.text();
  return (text.length > 0 ? JSON.parse(text) : undefined) as T;
}
