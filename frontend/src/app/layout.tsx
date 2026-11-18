import type { Metadata } from "next";
import { IntlProvider } from "@/shared/lib/i18n/provider";
import getRequestConfig from "@/shared/lib/i18n/request";
import "./globals.css";

export const metadata: Metadata = {
  title: "Restofy",
};

// The document language depends on the request cookie, so the root renders per request.
export const instant = false;

export default async function RootLayout({ children }: LayoutProps<"/">) {
  const config = await getRequestConfig();

  return (
    <html lang={config.locale}>
      <body>
        <IntlProvider {...config}>{children}</IntlProvider>
      </body>
    </html>
  );
}
