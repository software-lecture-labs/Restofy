import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Theme } from "./config";

const { cookiesMock, setCookie, getCookie } = vi.hoisted(() => ({
  cookiesMock: vi.fn(),
  setCookie: vi.fn(),
  getCookie: vi.fn(),
}));

vi.mock("next/headers", () => ({ cookies: cookiesMock }));

import { setTheme } from "./actions";
import { isTheme } from "./config";
import { getTheme } from "./get-theme";

beforeEach(() => {
  vi.clearAllMocks();
  cookiesMock.mockResolvedValue({ set: setCookie, get: getCookie });
});
afterEach(() => vi.unstubAllEnvs());

describe("theme preference", () => {
  it.each(["light", "dark"] as const)("persists the %s theme", async (theme) => {
    vi.stubEnv("NODE_ENV", "development");
    await setTheme(theme);
    expect(setCookie).toHaveBeenCalledWith("theme", theme, {
      path: "/",
      httpOnly: true,
      sameSite: "lax",
      secure: false,
      maxAge: 60 * 60 * 24 * 365,
    });
  });

  it("requires a secure cookie in production", async () => {
    vi.stubEnv("NODE_ENV", "production");
    await setTheme("dark");
    expect(setCookie).toHaveBeenCalledWith(
      "theme",
      "dark",
      expect.objectContaining({ secure: true }),
    );
  });

  it("rejects unsupported input before accessing cookies", async () => {
    await expect(setTheme("sepia" as Theme)).rejects.toThrow("Unsupported theme");
    expect(cookiesMock).not.toHaveBeenCalled();
  });

  it.each(["light", "dark"])("reads the saved %s theme", async (value) => {
    getCookie.mockReturnValue({ value });
    await expect(getTheme()).resolves.toBe(value);
  });

  it.each([undefined, { value: "sepia" }])("falls back to the system preference for %j", async (cookie) => {
    getCookie.mockReturnValue(cookie);
    await expect(getTheme()).resolves.toBeUndefined();
  });

  it("recognises only the supported themes", () => {
    expect(isTheme("dark")).toBe(true);
    expect(isTheme("auto")).toBe(false);
    expect(isTheme(undefined)).toBe(false);
  });
});
