import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { getLocale, getTheme } = vi.hoisted(() => ({ getLocale: vi.fn(), getTheme: vi.fn() }));
vi.mock("next-intl/server", () => ({ getLocale }));
vi.mock("@/shared/lib/theme/get-theme", () => ({ getTheme }));
vi.mock("next-intl", () => ({
  NextIntlClientProvider: ({ children }: { children: ReactNode }) => children,
}));

import RootLayout from "./layout";

async function render() {
  return renderToStaticMarkup(
    await RootLayout({
      children: <main>Application content</main>,
      params: Promise.resolve({}),
    }),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  getTheme.mockResolvedValue(undefined);
});

describe("document language", () => {
  it.each(["tr", "en"])("renders %s in the initial HTML", async (locale) => {
    getLocale.mockResolvedValue(locale);
    const html = await render();
    expect(html).toContain(`<html lang="${locale}">`);
    expect(html).toContain("<main>Application content</main>");
  });
});

describe("document theme", () => {
  it.each(["light", "dark"])("renders the saved %s theme in the initial HTML", async (theme) => {
    getLocale.mockResolvedValue("tr");
    getTheme.mockResolvedValue(theme);
    expect(await render()).toContain(`<html lang="tr" data-theme="${theme}">`);
  });

  it("leaves the theme to the system when none is saved", async () => {
    getLocale.mockResolvedValue("tr");
    expect(await render()).not.toContain("data-theme");
  });
});
