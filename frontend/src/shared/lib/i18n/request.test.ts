import { beforeEach, describe, expect, it, vi } from "vitest";

const { cookiesMock, getCookie } = vi.hoisted(() => ({
  cookiesMock: vi.fn(),
  getCookie: vi.fn(),
}));

vi.mock("next/headers", () => ({ cookies: cookiesMock }));
vi.mock("next-intl/server", () => ({
  getRequestConfig: (factory: unknown) => factory,
}));

import getRequestConfig from "./request";

beforeEach(() => {
  vi.clearAllMocks();
  getCookie.mockReturnValue(undefined);
  cookiesMock.mockResolvedValue({ get: getCookie });
});

describe("request language", () => {
  it.each([undefined, "de", "../../outside"])("falls back to Turkish for %s", async (value) => {
    getCookie.mockReturnValue(value === undefined ? undefined : { value });
    const config = await getRequestConfig({ requestLocale: Promise.resolve(undefined) });
    expect(config.locale).toBe("tr");
    expect(config.timeZone).toBe("Europe/Istanbul");
    expect(getCookie).toHaveBeenCalledWith("locale");
  });

  it.each(["tr", "en"] as const)("uses the supported %s cookie", async (value) => {
    getCookie.mockReturnValue({ value });
    const config = await getRequestConfig({ requestLocale: Promise.resolve(undefined) });
    expect(config.locale).toBe(value);
  });

  it("uses an explicit locale without reading the request cookie", async () => {
    const config = await getRequestConfig({
      locale: "en",
      requestLocale: Promise.resolve(undefined),
    });
    expect(config.locale).toBe("en");
    expect(cookiesMock).not.toHaveBeenCalled();
  });
});
