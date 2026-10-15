import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Locale } from "./config";

const { cookiesMock, setCookie } = vi.hoisted(() => ({
  cookiesMock: vi.fn(),
  setCookie: vi.fn(),
}));

vi.mock("next/headers", () => ({ cookies: cookiesMock }));

import { setLocale } from "./actions";

beforeEach(() => {
  vi.clearAllMocks();
  cookiesMock.mockResolvedValue({ set: setCookie });
});
afterEach(() => vi.unstubAllEnvs());

describe("locale preference", () => {
  it.each(["tr", "en"] as const)("persists the supported %s locale", async (locale) => {
    vi.stubEnv("NODE_ENV", "development");
    await setLocale(locale);
    expect(setCookie).toHaveBeenCalledWith("locale", locale, {
      path: "/",
      httpOnly: true,
      sameSite: "lax",
      secure: false,
      maxAge: 60 * 60 * 24 * 365,
    });
  });

  it("rejects unsupported input before accessing cookies", async () => {
    await expect(setLocale("de" as Locale)).rejects.toThrow("Unsupported locale");
    expect(cookiesMock).not.toHaveBeenCalled();
    expect(setCookie).not.toHaveBeenCalled();
  });

  it("requires a secure cookie in production", async () => {
    vi.stubEnv("NODE_ENV", "production");
    await setLocale("en");
    expect(setCookie).toHaveBeenCalledWith(
      "locale",
      "en",
      expect.objectContaining({ secure: true }),
    );
  });
});
