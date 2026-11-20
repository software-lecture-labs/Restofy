import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/lib/api";

const locale = vi.hoisted(() => ({ value: "tr" }));
const api = vi.hoisted(() => ({
  requestPasswordReset: vi.fn(),
  verifyPasswordResetCode: vi.fn(),
  confirmPasswordReset: vi.fn(),
}));

vi.mock("next-intl", () => ({ useLocale: () => locale.value }));
vi.mock("../api/auth-api", () => api);

import { usePasswordReset } from "./use-password-reset";

beforeEach(() => {
  vi.clearAllMocks();
  locale.value = "tr";
});

describe("usePasswordReset", () => {
  it("starts on the request step without a problem", () => {
    const { result } = renderHook(() => usePasswordReset());
    expect(result.current).toMatchObject({ step: "request", problem: null, pending: false });
  });

  it("walks request, code and password to done", async () => {
    api.requestPasswordReset.mockResolvedValue(undefined);
    api.verifyPasswordResetCode.mockResolvedValue(undefined);
    api.confirmPasswordReset.mockResolvedValue(undefined);
    const { result } = renderHook(() => usePasswordReset());

    await act(() => result.current.submitRequest("admin@restofy.com", "123456"));
    expect(result.current.step).toBe("code");
    await act(() => result.current.submitCode("654321"));
    expect(result.current.step).toBe("password");
    await act(() => result.current.submitPassword("a-new-long-password"));
    expect(result.current.step).toBe("done");

    expect(api.requestPasswordReset).toHaveBeenCalledWith("admin@restofy.com", "123456", "tr");
    expect(api.verifyPasswordResetCode).toHaveBeenCalledWith("654321");
    expect(api.confirmPasswordReset).toHaveBeenCalledWith("a-new-long-password");
  });

  it("sends the English locale only for English and Turkish for anything else", async () => {
    api.requestPasswordReset.mockResolvedValue(undefined);
    locale.value = "en";
    const english = renderHook(() => usePasswordReset());
    await act(() => english.result.current.submitRequest("a@b.co", "123456"));
    expect(api.requestPasswordReset).toHaveBeenLastCalledWith("a@b.co", "123456", "en");

    locale.value = "de";
    const other = renderHook(() => usePasswordReset());
    await act(() => other.result.current.submitRequest("a@b.co", "123456"));
    expect(api.requestPasswordReset).toHaveBeenLastCalledWith("a@b.co", "123456", "tr");
  });

  it("is pending while a call is in flight", async () => {
    let finish: () => void = () => {};
    api.requestPasswordReset.mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));
    const { result } = renderHook(() => usePasswordReset());

    let done: Promise<void> = Promise.resolve();
    act(() => {
      done = result.current.submitRequest("a@b.co", "123456");
    });
    expect(result.current.pending).toBe(true);
    await act(async () => {
      finish();
      await done;
    });
    expect(result.current.pending).toBe(false);
  });

  it.each([
    ["request", 400, "invalidInput"],
    ["request", 401, "invalidRequest"],
    ["request", 429, "tooManyAttempts"],
    ["request", 500, "unavailable"],
  ])("maps a %s failure with status %i to %s and stays on the step", async (_step, status, problem) => {
    api.requestPasswordReset.mockRejectedValue(new ApiError(status));
    const { result } = renderHook(() => usePasswordReset());

    await act(() => result.current.submitRequest("a@b.co", "123456"));

    expect(result.current.problem).toBe(problem);
    expect(result.current.step).toBe("request");
    expect(result.current.pending).toBe(false);
  });

  it("names a wrong e-mailed code", async () => {
    api.requestPasswordReset.mockResolvedValue(undefined);
    api.verifyPasswordResetCode.mockRejectedValue(new ApiError(401));
    const { result } = renderHook(() => usePasswordReset());
    await act(() => result.current.submitRequest("a@b.co", "123456"));

    await act(() => result.current.submitCode("000000"));

    expect(result.current.problem).toBe("invalidCode");
    expect(result.current.step).toBe("code");
  });

  it("calls a refused password weak and keeps the step", async () => {
    api.requestPasswordReset.mockResolvedValue(undefined);
    api.verifyPasswordResetCode.mockResolvedValue(undefined);
    api.confirmPasswordReset.mockRejectedValue(new ApiError(400));
    const { result } = renderHook(() => usePasswordReset());
    await act(() => result.current.submitRequest("a@b.co", "123456"));
    await act(() => result.current.submitCode("654321"));

    await act(() => result.current.submitPassword("a-new-long-password"));

    expect(result.current.problem).toBe("weakPassword");
    expect(result.current.step).toBe("password");
  });

  it("starts over when the reset has expired", async () => {
    api.requestPasswordReset.mockResolvedValue(undefined);
    api.verifyPasswordResetCode.mockResolvedValue(undefined);
    api.confirmPasswordReset.mockRejectedValue(new ApiError(401));
    const { result } = renderHook(() => usePasswordReset());
    await act(() => result.current.submitRequest("a@b.co", "123456"));
    await act(() => result.current.submitCode("654321"));

    await act(() => result.current.submitPassword("a-new-long-password"));

    expect(result.current.problem).toBe("expired");
    expect(result.current.step).toBe("request");
  });

  it("treats a failure that is not an API answer as unavailable", async () => {
    api.requestPasswordReset.mockRejectedValue(new Error("boom"));
    const { result } = renderHook(() => usePasswordReset());

    await act(() => result.current.submitRequest("a@b.co", "123456"));

    expect(result.current.problem).toBe("unavailable");
  });

  it("clears the problem when the next attempt starts", async () => {
    api.requestPasswordReset.mockRejectedValueOnce(new ApiError(401)).mockResolvedValueOnce(undefined);
    const { result } = renderHook(() => usePasswordReset());
    await act(() => result.current.submitRequest("a@b.co", "123456"));
    expect(result.current.problem).toBe("invalidRequest");

    await act(() => result.current.submitRequest("a@b.co", "123456"));

    expect(result.current.problem).toBeNull();
    expect(result.current.step).toBe("code");
  });

  it("goes back to the request step and drops the problem on restart", async () => {
    api.requestPasswordReset.mockResolvedValue(undefined);
    api.verifyPasswordResetCode.mockRejectedValue(new ApiError(401));
    const { result } = renderHook(() => usePasswordReset());
    await act(() => result.current.submitRequest("a@b.co", "123456"));
    await act(() => result.current.submitCode("000000"));

    act(() => result.current.restart());

    expect(result.current).toMatchObject({ step: "request", problem: null });
  });
});
