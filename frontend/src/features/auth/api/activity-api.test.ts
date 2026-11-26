import { beforeEach, describe, expect, it, vi } from "vitest";

const client = vi.hoisted(() => ({ apiRequest: vi.fn() }));
vi.mock("@/shared/lib/api", () => client);

import { recordActivity } from "./activity-api";

beforeEach(() => {
  vi.clearAllMocks();
});

describe("recordActivity", () => {
  it("posts to the Auth activity endpoint; the client adds the CSRF token to every POST", async () => {
    client.apiRequest.mockResolvedValue(undefined);

    await recordActivity();

    expect(client.apiRequest).toHaveBeenCalledWith("/api/v1/auth/activity", { method: "POST" });
  });
});
