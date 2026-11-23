import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getLocale } from "next-intl/server";
import { getTheme } from "@/shared/lib/theme/get-theme";
import "./globals.css";

export const metadata: Metadata = {
  title: "Restofy",
};

// The document language and theme depend on request cookies, so the root renders per request.
export const instant = false;

export default async function RootLayout({ children }: LayoutProps<"/">) {
  const [locale, theme] = await Promise.all([getLocale(), getTheme()]);

  return (
    <html lang={locale} data-theme={theme}>
      <body>
        <NextIntlClientProvider>{children}</NextIntlClientProvider>
      </body>
    </html>
  );
}
