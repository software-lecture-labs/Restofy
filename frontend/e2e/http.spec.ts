import { expect, test } from "@playwright/test";

test("production frontend responds with an HTML document", async ({ request }) => {
  const response = await request.get("/", { maxRedirects: 0 });
  expect(response.status()).toBe(200);
  expect(response.headers()["content-type"]).toContain("text/html");
  const html = await response.text();
  expect(html).toContain("<title>Restofy</title>");
  expect(html).toMatch(/<html[^>]*lang="tr"/);
});
