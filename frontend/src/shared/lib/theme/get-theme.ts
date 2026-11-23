import { cookies } from "next/headers";
import { isTheme, themeCookieName, type Theme } from "./config";

/** The saved theme, or undefined so that the operating system preference applies. */
export async function getTheme(): Promise<Theme | undefined> {
  const value = (await cookies()).get(themeCookieName)?.value;
  return isTheme(value) ? value : undefined;
}
