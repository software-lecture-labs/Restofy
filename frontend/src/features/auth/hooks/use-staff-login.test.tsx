import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/lib/api";

const replace = vi.hoisted(() => vi.fn());
const api = vi.hoisted(() => ({
  login: vi.fn(),
  getSession: vi.fn(),
  verifyTotp: vi.fn(),
  startMfaEnrollment: vi.fn(),
  confirmMfaEnrollment: vi.fn(),
  recoverWithCode: vi.fn(),
  logout: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("../api/auth-api", () => api);

import type { StaffRole } from "../config/staff-roles";
import { useStaffLogin } from "./use-staff-login";

const session = (overrides = {}) => ({
  status: "AUTHENTICATED",
  role: "ADMIN",
  idleExpiresAt: "2030-01-01T00:00:00Z",
  absoluteExpiresAt: "2030-01-01T08:00:00Z",
  ...overrides,
});
const credentials = { email: "admin@restofy.com", password: "secret-password" };
const enrollment = { secret: "JBSWY3DPEHPK3PXP", otpauthUri: "otpauth://totp/x?secret=A", expiresAt: "2030-01-01T00:10:00Z" };

function setup(role: StaffRole = "ADMIN") {
  return renderHook(() => useStaffLogin(role));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.logout.mockResolvedValue(undefined);
});

describe("useStaffLogin", () => {
  it("starts on the credentials step", () => {
    const { result } = setup();
    expect(result.current).toMatchObject({ step: "credentials", problem: null, pending: false, enrollment: null });
  });

  it("enters the application after a plain sign-in", async () => {
    api.login.mockResolvedValue(session());
    const { result } = setup();

    await act(() => result.current.submitCredentials(credentials));

    expect(api.login).toHaveBeenCalledWith("admin@restofy.com", "secret-password");
    expect(replace).toHaveBeenCalledWith("/");
  });

  it("is pending while the call is in flight", async () => {
    let finish: (value: unknown) => void = () => {};
    api.login.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const { result } = setup();

    let done: Promise<void> = Promise.resolve();
    act(() => {
      done = result.current.submitCredentials(credentials);
    });
    expect(result.current.pending).toBe(true);
    await act(async () => {
      finish(session());
      await done;
    });
    expect(result.current.pending).toBe(false);
  });

  it.each([
    [400, "invalidInput"],
    [401, "invalidCredentials"],
    [429, "tooManyAttempts"],
    [503, "unavailable"],
  ])("maps status %i of a failed sign-in to %s", async (status, problem) => {
    api.login.mockRejectedValue(new ApiError(status));
    const { result } = setup();

    await act(() => result.current.submitCredentials(credentials));

    expect(result.current.problem).toBe(problem);
    expect(result.current.step).toBe("credentials");
    expect(replace).not.toHaveBeenCalled();
  });

  it("treats a failure that is not an API answer as unavailable", async () => {
    api.login.mockRejectedValue(new Error("boom"));
    const { result } = setup();

    await act(() => result.current.submitCredentials(credentials));

    expect(result.current.problem).toBe("unavailable");
  });

  it("ends the session instead of using one with another role than the chosen tab", async () => {
    api.login.mockResolvedValue(session({ role: "KITCHEN" }));
    const { result } = setup("ADMIN");

    await act(() => result.current.submitCredentials(credentials));

    expect(api.logout).toHaveBeenCalled();
    expect(result.current.problem).toBe("wrongRole");
    expect(result.current.step).toBe("credentials");
    expect(replace).not.toHaveBeenCalled();
  });

  it("still reports the wrong role when the logout call itself fails", async () => {
    api.login.mockResolvedValue(session({ role: "KITCHEN" }));
    api.logout.mockRejectedValue(new ApiError(500));
    const { result } = setup("ADMIN");

    await act(() => result.current.submitCredentials(credentials));

    expect(result.current.problem).toBe("wrongRole");
  });

  it("asks the session endpoint whether to enrol, and goes to the code step when MFA exists", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: false }));
    const { result } = setup();

    await act(() => result.current.submitCredentials(credentials));

    expect(api.getSession).toHaveBeenCalledTimes(1);
    expect(api.startMfaEnrollment).not.toHaveBeenCalled();
    expect(result.current.step).toBe("mfa");
  });

  it("starts enrolment for an admin without an authenticator", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: true }));
    api.startMfaEnrollment.mockResolvedValue(enrollment);
    const { result } = setup();

    await act(() => result.current.submitCredentials(credentials));

    expect(result.current.step).toBe("mfaSetup");
    expect(result.current.enrollment).toEqual(enrollment);
  });

  it("ends the half-finished session when the enrolment status cannot be read", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockRejectedValue(new ApiError(500));
    const { result } = setup();

    await act(() => result.current.submitCredentials(credentials));

    expect(api.logout).toHaveBeenCalled();
    expect(result.current.problem).toBe("unavailable");
    expect(result.current.step).toBe("credentials");
  });

  it("enters the application after a correct authenticator code", async () => {
    api.verifyTotp.mockResolvedValue(session());
    const { result } = setup();

    await act(() => result.current.submitCode("123456"));

    expect(api.verifyTotp).toHaveBeenCalledWith("123456");
    expect(replace).toHaveBeenCalledWith("/");
  });

  it("names a wrong authenticator code", async () => {
    api.verifyTotp.mockRejectedValue(new ApiError(401));
    const { result } = setup();

    await act(() => result.current.submitCode("000000"));

    expect(result.current.problem).toBe("invalidCode");
  });

  it("signs in with a recovery code", async () => {
    api.recoverWithCode.mockResolvedValue(session());
    const { result } = setup();

    await act(() => result.current.submitRecoveryCode("abcd-efgh-ijkl-mnop-qrst"));

    expect(api.recoverWithCode).toHaveBeenCalledWith("abcd-efgh-ijkl-mnop-qrst");
    expect(replace).toHaveBeenCalledWith("/");
  });

  it("names a used or wrong recovery code", async () => {
    api.recoverWithCode.mockRejectedValue(new ApiError(401));
    const { result } = setup();

    await act(() => result.current.submitRecoveryCode("abcd-efgh-ijkl-mnop-qrst"));

    expect(result.current.problem).toBe("invalidCode");
  });

  it("shows the recovery codes after enrolment is confirmed and leaves only on finish", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: true }));
    api.startMfaEnrollment.mockResolvedValue(enrollment);
    api.confirmMfaEnrollment.mockResolvedValue({ session: session(), recoveryCodes: ["aaaa-bbbb-cccc-dddd-eeee"] });
    const { result } = setup();
    await act(() => result.current.submitCredentials(credentials));

    await act(() => result.current.confirmEnrollment("654321"));

    expect(api.confirmMfaEnrollment).toHaveBeenCalledWith("654321");
    expect(result.current.step).toBe("recoveryCodes");
    expect(result.current.recoveryCodes).toEqual(["aaaa-bbbb-cccc-dddd-eeee"]);
    expect(result.current.enrollment).toBeNull();
    expect(replace).not.toHaveBeenCalled();

    act(() => result.current.finish());

    expect(result.current.recoveryCodes).toEqual([]);
    expect(replace).toHaveBeenCalledWith("/");
  });

  it("keeps the enrolment step and names the problem when the confirming code is wrong", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: true }));
    api.startMfaEnrollment.mockResolvedValue(enrollment);
    api.confirmMfaEnrollment.mockRejectedValue(new ApiError(401));
    const { result } = setup();
    await act(() => result.current.submitCredentials(credentials));

    await act(() => result.current.confirmEnrollment("000000"));

    expect(result.current.step).toBe("mfaSetup");
    expect(result.current.problem).toBe("invalidCode");
    expect(result.current.enrollment).toEqual(enrollment);
  });

  it("clears a shown problem on request", async () => {
    api.login.mockRejectedValue(new ApiError(401));
    const { result } = setup();
    await act(() => result.current.submitCredentials(credentials));
    expect(result.current.problem).toBe("invalidCredentials");

    act(() => result.current.clearProblem());

    expect(result.current.problem).toBeNull();
  });

  it("returns to the credentials step and ends the session on restart", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: true }));
    api.startMfaEnrollment.mockResolvedValue(enrollment);
    const { result } = setup();
    await act(() => result.current.submitCredentials(credentials));

    await act(() => result.current.restart());

    expect(api.logout).toHaveBeenCalled();
    expect(result.current).toMatchObject({ step: "credentials", problem: null, enrollment: null });
  });
});
