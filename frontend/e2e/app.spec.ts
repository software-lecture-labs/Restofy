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

test("login works on desktop with safe credential errors and a pausable illustration", async ({ page }, testInfo) => {
  await page.route("**/api/v1/auth/csrf", route => route.fulfill({ json: { token: "test-csrf" } }));
  await page.route("**/api/v1/auth/login", async route => {
    expect(route.request().headers()["x-csrf-token"]).toBe("test-csrf");
    await route.fulfill({ status: 401, json: {} });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/login");
  await expect(page).toHaveTitle("Giriş yap | Restofy");
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);
  await page.screenshot({ path: testInfo.outputPath("login-desktop.png"), fullPage: true, animations: "disabled" });
  await page.getByLabel("E-posta adresi").fill("fixture@example.com");
  await page.getByLabel("Parola", { exact: true }).fill("fixture-password");
  await page.getByRole("button", { name: "Parolayı göster" }).click();
  await expect(page.getByLabel("Parola", { exact: true })).toHaveAttribute("type", "text");
  await page.getByRole("button", { name: "Giriş yap", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("E-posta veya parola hatalı");
  await expect(page.getByRole("main").getByRole("alert")).toBeFocused();
  await page.getByRole("button", { name: "Dekoratif animasyonu duraklat" }).click();
  await expect(page.getByRole("button", { name: "Dekoratif animasyonu sürdür" })).toHaveAttribute("aria-pressed", "true");
});

test("mobile login reflows, switches locale and respects reduced motion", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "Tekrar hoş geldin." })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await expect(page.getByRole("complementary")).toBeHidden();
  await page.screenshot({ path: testInfo.outputPath("login-mobile.png"), fullPage: true, animations: "disabled" });
  expect(await page.getByLabel("Restofy", { exact: true }).locator("span span").first()
    .evaluate(element => getComputedStyle(element).animationName)).toBe("none");
  await page.getByLabel("Dil seçimi").selectOption("en");
  await expect(page.getByRole("heading", { name: "Welcome back." })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await page.reload();
  await expect(page.getByLabel("Email address")).toBeVisible();
});
