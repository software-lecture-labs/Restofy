"use server";

import { cookies } from "next/headers";
import { isTheme, themeCookieName, type Theme } from "./config";

export async function setTheme(theme: Theme): Promise<void> {
  if (!isTheme(theme)) {
    throw new Error("Unsupported theme");
  }

  const store = await cookies();
  store.set(themeCookieName, theme, {
    path: "/",
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    maxAge: 60 * 60 * 24 * 365,
  });
}
