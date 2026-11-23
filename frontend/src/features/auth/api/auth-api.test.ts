import { beforeEach, describe, expect, it, vi } from "vitest";

const apiRequest = vi.hoisted(() => vi.fn());
vi.mock("@/shared/lib/api", () => ({ apiRequest }));

import * as authApi from "./auth-api";

beforeEach(() => {
  vi.clearAllMocks();
  apiRequest.mockResolvedValue(undefined);
});

/** The paths, methods and bodies are the contract with the Auth service; a typo here breaks sign-in. */
describe("auth api", () => {
  it.each([
    ["login", () => authApi.login("a@b.co", "pw"), "/api/v1/auth/login", { method: "POST", body: { email: "a@b.co", password: "pw" } }],
    ["verifyTotp", () => authApi.verifyTotp("123456"), "/api/v1/auth/mfa/verify", { method: "POST", body: { code: "123456" } }],
    ["startMfaEnrollment", () => authApi.startMfaEnrollment(), "/api/v1/auth/mfa/enroll", { method: "POST" }],
    [
      "confirmMfaEnrollment",
      () => authApi.confirmMfaEnrollment("654321"),
      "/api/v1/auth/mfa/enroll/confirm",
      { method: "POST", body: { code: "654321" } },
    ],
    [
      "recoverWithCode",
      () => authApi.recoverWithCode("abcd-efgh-ijkl-mnop-qrst"),
      "/api/v1/auth/mfa/recover",
      { method: "POST", body: { code: "abcd-efgh-ijkl-mnop-qrst" } },
    ],
    [
      "requestPasswordReset",
      () => authApi.requestPasswordReset("a@b.co", "123456", "en"),
      "/api/v1/auth/password-reset/request",
      { method: "POST", body: { email: "a@b.co", totpCode: "123456", locale: "en" } },
    ],
    [
      "verifyPasswordResetCode",
      () => authApi.verifyPasswordResetCode("654321"),
      "/api/v1/auth/password-reset/verify",
      { method: "POST", body: { code: "654321" } },
    ],
    [
      "confirmPasswordReset",
      () => authApi.confirmPasswordReset("a-new-long-password"),
      "/api/v1/auth/password-reset/confirm",
      { method: "POST", body: { newPassword: "a-new-long-password" } },
    ],
    ["refresh", () => authApi.refresh(), "/api/v1/auth/refresh", { method: "POST" }],
    ["logout", () => authApi.logout(), "/api/v1/auth/logout", { method: "POST" }],
  ])("%s calls %s with the agreed method and body", async (_name, call, path, options) => {
    await call();
    expect(apiRequest).toHaveBeenCalledWith(path, options);
  });

  it("shares one refresh request between callers that ask at the same time", async () => {
    let finish: (session: unknown) => void = () => undefined;
    apiRequest.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));

    const first = authApi.refresh();
    const second = authApi.refresh();
    finish({ status: "AUTHENTICATED" });
    await Promise.all([first, second]);

    expect(apiRequest).toHaveBeenCalledOnce();
    // Once it has finished, the next refresh is a new request.
    await authApi.refresh();
    expect(apiRequest).toHaveBeenCalledTimes(2);
  });

  it("reads the session with a plain GET", async () => {
    await authApi.getSession();
    expect(apiRequest).toHaveBeenCalledWith("/api/v1/auth/session");
  });

  it("returns what the client answers", async () => {
    const session = { status: "AUTHENTICATED", role: "ADMIN" };
    apiRequest.mockResolvedValueOnce(session);
    await expect(authApi.getSession()).resolves.toBe(session);
  });

  it("lets a client failure through unchanged", async () => {
    const failure = new Error("boom");
    apiRequest.mockRejectedValueOnce(failure);
    await expect(authApi.login("a@b.co", "pw")).rejects.toBe(failure);
  });
});
