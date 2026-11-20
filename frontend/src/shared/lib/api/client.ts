/**
 * Minimal client for the backend, always reached through the API Gateway. The staff session lives in HttpOnly
 * cookies, so every request carries credentials and no token is ever read or stored by this code. Changing
 * requests also send the CSRF token the Auth service issues (header X-CSRF-TOKEN).
 */

/**
 * Empty by default: requests go to this same origin and next.config.ts forwards /api to the Gateway. Setting
 * NEXT_PUBLIC_API_BASE_URL makes the browser call that address directly instead (it must then answer CORS).
 */
export const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/+$/, "");

/** status 0 means the request never got an answer (offline, gateway down, blocked by CORS). */
export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`API request failed (${status})`);
    this.name = "ApiError";
  }
}

type RequestOptions = {
  method?: "GET" | "POST";
  body?: unknown;
};

const CSRF_PATH = "/api/v1/auth/csrf";

/** The Auth service issues a fresh CSRF token whenever the session changes, so the cached one is dropped then. */
const CSRF_ROTATING_PATHS = ["/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/mfa/"];

let csrfToken: Promise<string> | null = null;

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(`${apiBaseUrl}${path}`, { ...init, credentials: "include", cache: "no-store" });
  } catch {
    throw new ApiError(0);
  }
}

function loadCsrfToken(): Promise<string> {
  csrfToken ??= send(CSRF_PATH, { method: "GET" })
    .then(async (response) => {
      if (!response.ok) throw new ApiError(response.status);
      const { token } = (await response.json()) as { token: string };
      return token;
    })
    .catch((error: unknown) => {
      csrfToken = null;
      throw error;
    });
  return csrfToken;
}

async function post(path: string, body: unknown): Promise<Response> {
  const attempt = async () =>
    send(path, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": await loadCsrfToken() },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  const response = await attempt();
  if (response.status !== 403) return response;
  // The token may simply be stale: fetch a new one and try once more.
  csrfToken = null;
  return attempt();
}

export async function apiRequest<T = void>(path: string, { method = "GET", body }: RequestOptions = {}): Promise<T> {
  const response = method === "GET" ? await send(path, { method }) : await post(path, body);
  if (!response.ok) throw new ApiError(response.status);

  if (CSRF_ROTATING_PATHS.some((prefix) => path.startsWith(prefix))) csrfToken = null;
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
