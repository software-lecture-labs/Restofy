import { renderToStaticMarkup } from "react-dom/server";
import { useLocale, useTimeZone } from "use-intl/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { getRequestConfig } = vi.hoisted(() => ({ getRequestConfig: vi.fn() }));
vi.mock("@/shared/lib/i18n/request", () => ({ default: getRequestConfig }));

import RootLayout from "./layout";

beforeEach(() => vi.clearAllMocks());

function ApplicationContent() {
  const locale = useLocale();
  const timeZone = useTimeZone();
  return <main>{locale} {timeZone}</main>;
}

describe("document language", () => {
  it.each(["tr", "en"])("renders %s in the initial HTML", async (locale) => {
    getRequestConfig.mockResolvedValue({ locale, timeZone: "Europe/Istanbul", messages: {} });
    const html = renderToStaticMarkup(
      await RootLayout({
        children: <ApplicationContent />,
        params: Promise.resolve({}),
      }),
    );
    expect(html).toContain(`<html lang="${locale}">`);
    expect(html).toMatch(new RegExp(`<main>${locale}.*Europe/Istanbul</main>`));
  });
});
