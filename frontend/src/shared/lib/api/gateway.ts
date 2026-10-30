export type ApiFailure = "network" | "configuration" | "response";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly kind: ApiFailure = "response",
    public readonly retryAfter?: number,
  ) {
    super("Gateway request failed");
  }
}

/** Browser transport only. Cookies remain under the browser's HttpOnly policy. */
export async function gatewayRequest(path: string, init: RequestInit = {}): Promise<Response> {
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "";
  if (configured) {
    let base: URL;
    try { base = new URL(configured); } catch { throw new ApiError(0, "configuration"); }
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
      throw new ApiError(0, "configuration");
    }
  }
  let response: Response;
  try {
    response = await fetch(`${configured}${path}`, {
      ...init, credentials: "include", cache: "no-store", redirect: "error",
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new ApiError(0, "network");
  }
  if (!response.ok) {
    const retry = Number(response.headers.get("Retry-After"));
    throw new ApiError(response.status, "response", Number.isFinite(retry) && retry > 0 ? retry : undefined);
  }
  return response;
}
