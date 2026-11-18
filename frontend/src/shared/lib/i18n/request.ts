import { cookies } from "next/headers";
import { cache } from "react";
import { hasLocale } from "use-intl/core";
import { defaultLocale, localeCookieName, locales } from "./config";

export default cache(async (requestedLocale?: string) => {
  const candidate =
    requestedLocale ?? (await cookies()).get(localeCookieName)?.value;
  const locale = hasLocale(locales, candidate) ? candidate : defaultLocale;

  return {
    locale,
    messages: (await import(`./messages/${locale}.json`)).default,
    timeZone: "Europe/Istanbul",
  };
});
