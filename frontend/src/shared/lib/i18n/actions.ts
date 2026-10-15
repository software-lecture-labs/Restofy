"use server";

import { cookies } from "next/headers";
import { hasLocale } from "next-intl";
import { localeCookieName, locales, type Locale } from "./config";

export async function setLocale(locale: Locale): Promise<void> {
  if (!hasLocale(locales, locale)) {
    throw new Error("Unsupported locale");
  }

  const store = await cookies();
  store.set(localeCookieName, locale, {
    path: "/",
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    maxAge: 60 * 60 * 24 * 365,
  });
}
