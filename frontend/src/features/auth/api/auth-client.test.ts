import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authClient } from "./auth-client";
import { ApiError, gatewayRequest } from "@/shared/lib/api/gateway";

const fetchMock = vi.fn();
const active = {
  status: "AUTHENTICATED", role: "WAITER",
  idleExpiresAt: "2026-10-30T20:00:00Z", absoluteExpiresAt: "2026-11-01T20:00:00Z",
};
const json = (body: unknown, status = 200, headers: HeadersInit = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "http://localhost:5050");
  fetchMock.mockReset();
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("Auth browser contract", () => {
  it("uses Gateway credentials, obtains CSRF before login and renews its binding afterwards", async () => {
    fetchMock.mockResolvedValueOnce(json({ token: "before" }))
      .mockResolvedValueOnce(json(active)).mockResolvedValueOnce(json({ token: "after" }))
      .mockResolvedValueOnce(json(active));
    expect(await authClient.login("staff@example.com", "fixture-password")).toEqual(active);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "http://localhost:5050/api/v1/auth/csrf", "http://localhost:5050/api/v1/auth/login",
      "http://localhost:5050/api/v1/auth/csrf", "http://localhost:5050/api/v1/auth/session",
    ]);
    for (const [, options] of fetchMock.mock.calls) {
      expect(options).toMatchObject({ credentials: "include", cache: "no-store", redirect: "error" });
    }
    expect(fetchMock.mock.calls[1][1].headers).toEqual({ "Content-Type": "application/json", "X-CSRF-TOKEN": "before" });
    expect(fetchMock.mock.calls[1][1].body).toBe(JSON.stringify({ email: "staff@example.com", password: "fixture-password" }));
  });

  it("keeps MFA_PENDING distinct from authenticated staff", async () => {
    const pending = { ...active, role: "ADMIN", status: "MFA_PENDING", mfaSetupRequired: true };
    fetchMock.mockResolvedValueOnce(json({ token: "csrf" })).mockResolvedValueOnce(json(pending))
      .mockResolvedValueOnce(json({ token: "new-csrf" })).mockResolvedValueOnce(json(pending));
    expect((await authClient.login("admin@example.com", "fixture-password")).status).toBe("MFA_PENDING");
  });

  it("does not replay an uncertain login or mistake a pre-existing session for its result", async () => {
    fetchMock.mockResolvedValueOnce(json({ token: "csrf" })).mockRejectedValueOnce(new TypeError("connection lost"));
    await expect(authClient.login("staff@example.com", "fixture-password")).rejects.toMatchObject({ kind: "network" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("rejects invalid CSRF responses before sending any password", async () => {
    fetchMock.mockResolvedValueOnce(json({ token: "" }));
    await expect(authClient.login("staff@example.com", "fixture-password")).rejects.toMatchObject({ status: 502 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([
    { ...active, status: "UNKNOWN" }, { ...active, role: "GUEST" },
    { ...active, status: "MFA_PENDING" }, { ...active, idleExpiresAt: "invalid" },
  ])("rejects malformed or incompatible session claims: %j", async invalid => {
    fetchMock.mockResolvedValueOnce(json({ token: "a" })).mockResolvedValueOnce(json(active))
      .mockResolvedValueOnce(json({ token: "b" })).mockResolvedValueOnce(json(invalid));
    await expect(authClient.login("staff@example.com", "fixture-password")).rejects.toMatchObject({ status: 502 });
  });

  it("preserves rate-limit metadata without exposing backend error text", async () => {
    fetchMock.mockResolvedValue(json({ detail: "sensitive backend detail" }, 429, { "Retry-After": "60" }));
    await expect(gatewayRequest("/api/v1/auth/csrf")).rejects.toEqual(new ApiError(429, "response", 60));
  });

  it("uses a fresh CSRF token for logout", async () => {
    fetchMock.mockResolvedValueOnce(json({ token: "logout-csrf" })).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await authClient.logout();
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: "POST", headers: { "X-CSRF-TOKEN": "logout-csrf" } });
  });

  it("rejects credentials embedded in a Gateway URL without making a request", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://user:fixture@example.com");
    await expect(gatewayRequest("/api/v1/auth/csrf")).rejects.toMatchObject({ kind: "configuration" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
