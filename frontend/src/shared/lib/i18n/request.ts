import { cookies } from "next/headers";
import { hasLocale } from "next-intl";
import { getRequestConfig } from "next-intl/server";
import { defaultLocale, localeCookieName, locales } from "./config";

export default getRequestConfig(async ({ locale: requestedLocale }) => {
  const candidate =
    requestedLocale ?? (await cookies()).get(localeCookieName)?.value;
  const locale = hasLocale(locales, candidate) ? candidate : defaultLocale;

  return {
    locale,
    messages: (await import(`./messages/${locale}.json`)).default,
    timeZone: "Europe/Istanbul",
  };
});
