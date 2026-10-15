import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { getLocale } = vi.hoisted(() => ({ getLocale: vi.fn() }));
vi.mock("next-intl/server", () => ({ getLocale }));
vi.mock("next-intl", () => ({
  NextIntlClientProvider: ({ children }: { children: ReactNode }) => children,
}));

import RootLayout from "./layout";

beforeEach(() => vi.clearAllMocks());

describe("document language", () => {
  it.each(["tr", "en"])("renders %s in the initial HTML", async (locale) => {
    getLocale.mockResolvedValue(locale);
    const html = renderToStaticMarkup(
      await RootLayout({
        children: <main>Application content</main>,
        params: Promise.resolve({}),
      }),
    );
    expect(html).toContain(`<html lang="${locale}">`);
    expect(html).toContain("<main>Application content</main>");
  });
});
