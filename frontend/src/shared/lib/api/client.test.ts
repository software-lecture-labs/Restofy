import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type ClientModule = typeof import("./client");

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function csrf(token: string) {
  return json({ token });
}

/** The module keeps the cached CSRF token, so every test starts from a fresh copy. */
async function load(): Promise<ClientModule> {
  vi.resetModules();
  return import("./client");
}

function calls(path: string) {
  return fetchMock.mock.calls.filter(([url]) => url === path);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("api client", () => {
  it("reads with cookies, no cache and no CSRF token", async () => {
    fetchMock.mockResolvedValueOnce(json({ status: "AUTHENTICATED" }));
    const { apiRequest } = await load();

    await expect(apiRequest("/api/v1/auth/session")).resolves.toEqual({ status: "AUTHENTICATED" });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/auth/session");
    expect(init).toMatchObject({ method: "GET", credentials: "include", cache: "no-store" });
    expect(calls("/api/v1/auth/csrf")).toHaveLength(0);
  });

  it("sends the CSRF token and the JSON body on a changing request", async () => {
    fetchMock.mockResolvedValueOnce(csrf("tok-1")).mockResolvedValueOnce(json({ ok: true }));
    const { apiRequest } = await load();

    await apiRequest("/api/v1/auth/something", { method: "POST", body: { a: 1 } });

    const [, init] = fetchMock.mock.calls[1];
    expect(init.method).toBe("POST");
    expect(init.headers).toEqual({ "Content-Type": "application/json", "X-CSRF-TOKEN": "tok-1" });
    expect(init.body).toBe(JSON.stringify({ a: 1 }));
  });

  it("sends no body when none is given", async () => {
    fetchMock.mockResolvedValueOnce(csrf("tok-1")).mockResolvedValueOnce(new Response(null, { status: 204 }));
    const { apiRequest } = await load();

    await expect(apiRequest("/api/v1/auth/something", { method: "POST" })).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[1][1].body).toBeUndefined();
  });

  it("reuses the cached token for further requests", async () => {
    fetchMock
      .mockResolvedValueOnce(csrf("tok-1"))
      .mockResolvedValueOnce(json({}))
      .mockResolvedValueOnce(json({}));
    const { apiRequest } = await load();

    await apiRequest("/api/v1/auth/a", { method: "POST" });
    await apiRequest("/api/v1/auth/b", { method: "POST" });

    expect(calls("/api/v1/auth/csrf")).toHaveLength(1);
  });

  it.each([
    "/api/v1/auth/login",
    "/api/v1/auth/refresh",
    "/api/v1/auth/logout",
    "/api/v1/auth/mfa/verify",
    "/api/v1/auth/mfa/enroll/confirm",
    "/api/v1/auth/password/change",
  ])("fetches a new token after %s, because the session changed", async (path) => {
    fetchMock
      .mockResolvedValueOnce(csrf("tok-1"))
      .mockResolvedValueOnce(json({}))
      .mockResolvedValueOnce(csrf("tok-2"))
      .mockResolvedValueOnce(json({}));
    const { apiRequest } = await load();

    await apiRequest(path, { method: "POST" });
    await apiRequest("/api/v1/auth/next", { method: "POST" });

    expect(calls("/api/v1/auth/csrf")).toHaveLength(2);
    expect(fetchMock.mock.calls[3][1].headers["X-CSRF-TOKEN"]).toBe("tok-2");
  });

  it("keeps the token after a path that does not change the session", async () => {
    fetchMock.mockResolvedValueOnce(csrf("tok-1")).mockResolvedValueOnce(json({})).mockResolvedValueOnce(json({}));
    const { apiRequest } = await load();

    await apiRequest("/api/v1/auth/password-reset/verify", { method: "POST" });
    await apiRequest("/api/v1/auth/password-reset/confirm", { method: "POST" });

    expect(calls("/api/v1/auth/csrf")).toHaveLength(1);
  });

  it("retries once with a new token when the server answers 403", async () => {
    fetchMock
      .mockResolvedValueOnce(csrf("stale"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(csrf("fresh"))
      .mockResolvedValueOnce(json({ done: true }));
    const { apiRequest } = await load();

    await expect(apiRequest("/api/v1/auth/something", { method: "POST" })).resolves.toEqual({ done: true });
    expect(fetchMock.mock.calls[3][1].headers["X-CSRF-TOKEN"]).toBe("fresh");
  });

  it("fails with the status when the retry is refused too", async () => {
    fetchMock
      .mockResolvedValueOnce(csrf("a"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(csrf("b"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }));
    const { apiRequest, ApiError } = await load();

    await expect(apiRequest("/api/v1/auth/something", { method: "POST" })).rejects.toMatchObject({
      status: 403,
      name: "ApiError",
    });
    await expect(Promise.reject(new ApiError(500))).rejects.toThrow("API request failed (500)");
  });

  it("throws ApiError with the status for a failed read", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    const { apiRequest, ApiError } = await load();

    const failure = await apiRequest("/api/v1/auth/session").catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as InstanceType<typeof ApiError>).status).toBe(401);
  });

  it("closes the refused first answer before it retries with a new token", async () => {
    const refused = json({}, 403);
    const cancel = vi.spyOn(refused.body!, "cancel");
    fetchMock
      .mockResolvedValueOnce(csrf("old"))
      .mockResolvedValueOnce(refused)
      .mockResolvedValueOnce(csrf("new"))
      .mockResolvedValueOnce(json({}));
    const { apiRequest } = await load();

    await apiRequest("/api/v1/auth/refresh", { method: "POST" });
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("closes the body of a failed answer so the connection can finish", async () => {
    const response = json({ code: "UNAUTHORIZED" }, 401);
    const cancel = vi.spyOn(response.body!, "cancel");
    fetchMock.mockResolvedValueOnce(response);
    const { apiRequest } = await load();

    await apiRequest("/api/v1/auth/session").catch(() => undefined);
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("reports status 0 when the request never gets an answer", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const { apiRequest } = await load();

    await expect(apiRequest("/api/v1/auth/session")).rejects.toMatchObject({ status: 0 });
  });

  it("does not cache a failed CSRF lookup", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(csrf("tok-1"))
      .mockResolvedValueOnce(json({}));
    const { apiRequest } = await load();

    await expect(apiRequest("/api/v1/auth/a", { method: "POST" })).rejects.toMatchObject({ status: 503 });
    await expect(apiRequest("/api/v1/auth/a", { method: "POST" })).resolves.toEqual({});
    expect(calls("/api/v1/auth/csrf")).toHaveLength(2);
  });

  it("returns undefined for a 204 answer", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    const { apiRequest } = await load();

    await expect(apiRequest("/api/v1/auth/session")).resolves.toBeUndefined();
  });

  it("calls the configured base address directly, without a trailing slash", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://gateway.example.com//");
    fetchMock.mockResolvedValueOnce(json({}));
    const { apiRequest, apiBaseUrl } = await load();

    await apiRequest("/api/v1/auth/session");

    expect(apiBaseUrl).toBe("https://gateway.example.com");
    expect(fetchMock.mock.calls[0][0]).toBe("https://gateway.example.com/api/v1/auth/session");
  });
});
