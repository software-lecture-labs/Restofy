import { expect, test } from "@playwright/test";

for (const { name, cookie, language } of [
  { name: "default locale is Turkish", cookie: undefined, language: "tr" },
  { name: "Turkish cookie renders Turkish", cookie: "tr", language: "tr" },
  { name: "English cookie renders English", cookie: "en", language: "en" },
  { name: "unsupported locale falls back to Turkish", cookie: "invalid", language: "tr" },
]) {
  test(name, async ({ page, context, baseURL }) => {
    const errors: string[] = [];
    page.on("pageerror", error => errors.push(error.message));
    if (cookie) {
      await context.addCookies([{ name: "locale", value: cookie, url: baseURL!, httpOnly: true, sameSite: "Lax" }]);
    }
    const response = await page.goto("/");
    expect(response?.status()).toBe(200);
    await expect(page).toHaveTitle("Restofy");
    await expect(page.locator("html")).toHaveAttribute("lang", language);
    await page.waitForLoadState("networkidle");
    expect(errors).toEqual([]);
  });
}

test("English locale is preserved after reload", async ({ context, page, baseURL }) => {
  await context.addCookies([{ name: "locale", value: "en", url: baseURL!, httpOnly: true, sameSite: "Lax" }]);
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  const response = await page.reload();
  expect(response?.status()).toBe(200);
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});
