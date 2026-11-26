import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/lib/api";

const api = vi.hoisted(() => ({ getSession: vi.fn(), refresh: vi.fn() }));
const activity = vi.hoisted(() => ({ recordActivity: vi.fn() }));

vi.mock("../api/auth-api", () => api);
vi.mock("../api/activity-api", () => activity);

import { ACTIVITY_INTERVAL_MS, useStaffActivity } from "./use-staff-activity";

const session = (overrides = {}) => ({
  status: "AUTHENTICATED",
  role: "WAITER",
  idleExpiresAt: "2030-01-01T10:00:00Z",
  absoluteExpiresAt: "2030-01-01T18:00:00Z",
  ...overrides,
});

let visibility: DocumentVisibilityState = "visible";

/** Lets the pending promise chains of the hook run to the end. */
async function settle() {
  await act(async () => {
    for (let round = 0; round < 10; round++) await Promise.resolve();
  });
}

function click(target: EventTarget = document.body) {
  target.dispatchEvent(new Event("pointerdown", { bubbles: true }));
}

function pressKey(target: EventTarget = document.body) {
  target.dispatchEvent(new KeyboardEvent("keydown", { key: "a", bubbles: true }));
}

function later(milliseconds: number) {
  vi.setSystemTime(Date.now() + milliseconds);
}

async function becomeVisible(state: DocumentVisibilityState = "visible") {
  visibility = state;
  document.dispatchEvent(new Event("visibilitychange"));
  await settle();
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2030-01-01T09:00:00Z"));
  visibility = "visible";
  vi.spyOn(document, "visibilityState", "get").mockImplementation(() => visibility);
  activity.recordActivity.mockResolvedValue(undefined);
  api.getSession.mockResolvedValue(session());
  api.refresh.mockResolvedValue(session());
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("useStaffActivity", () => {
  it("reports nothing until the person does something", async () => {
    renderHook(() => useStaffActivity(vi.fn()));
    await settle();

    expect(activity.recordActivity).not.toHaveBeenCalled();
    expect(api.getSession).not.toHaveBeenCalled();
  });

  it("reports a click at once, then at most once a minute however many events follow", async () => {
    renderHook(() => useStaffActivity(vi.fn()));

    click();
    await settle();
    click();
    pressKey();
    later(ACTIVITY_INTERVAL_MS - 1);
    click();
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(1);

    later(1);
    pressKey();
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(2);
  });

  it("counts a key and a scroll inside any panel, but not a mere pointer move", async () => {
    const panel = document.createElement("div");
    document.body.append(panel);
    renderHook(() => useStaffActivity(vi.fn()));

    panel.dispatchEvent(new Event("pointermove", { bubbles: true }));
    await settle();
    expect(activity.recordActivity).not.toHaveBeenCalled();

    // A scroll event does not bubble; the hook still hears it because it listens in the capture phase.
    panel.dispatchEvent(new Event("scroll"));
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(1);

    later(ACTIVITY_INTERVAL_MS);
    pressKey(panel);
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(2);
    panel.remove();
  });

  it("sends one report at a time even when the previous one is slow", async () => {
    let finish: () => void = () => undefined;
    activity.recordActivity.mockReturnValueOnce(new Promise<void>((resolve) => (finish = resolve)));
    renderHook(() => useStaffActivity(vi.fn()));

    click();
    later(ACTIVITY_INTERVAL_MS);
    click();
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(1);

    finish();
    await settle();
    later(ACTIVITY_INTERVAL_MS);
    click();
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(2);
  });

  it("renews an expired access cookie and reports again instead of signing the person out", async () => {
    const onSignedOut = vi.fn();
    activity.recordActivity.mockRejectedValueOnce(new ApiError(401)).mockResolvedValueOnce(undefined);
    renderHook(() => useStaffActivity(onSignedOut));

    click();
    await settle();

    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(activity.recordActivity).toHaveBeenCalledTimes(2);
    expect(onSignedOut).not.toHaveBeenCalled();
  });

  it("signs the person out once when the refresh is refused too, and sends nothing afterwards", async () => {
    const onSignedOut = vi.fn();
    activity.recordActivity.mockRejectedValue(new ApiError(401));
    api.refresh.mockRejectedValue(new ApiError(401));
    renderHook(() => useStaffActivity(onSignedOut));

    click();
    await settle();
    later(ACTIVITY_INTERVAL_MS);
    click();
    await becomeVisible();

    expect(onSignedOut).toHaveBeenCalledTimes(1);
    expect(activity.recordActivity).toHaveBeenCalledTimes(1);
    expect(api.getSession).not.toHaveBeenCalled();
  });

  it("signs out when the report is still refused after a successful refresh", async () => {
    const onSignedOut = vi.fn();
    activity.recordActivity.mockRejectedValue(new ApiError(403));
    renderHook(() => useStaffActivity(onSignedOut));

    click();
    await settle();

    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(onSignedOut).toHaveBeenCalledTimes(1);
  });

  it("keeps the person signed in when the Auth service is merely unreachable, and tries again a minute later", async () => {
    const onSignedOut = vi.fn();
    activity.recordActivity.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(undefined);
    renderHook(() => useStaffActivity(onSignedOut));

    click();
    await settle();
    expect(api.refresh).not.toHaveBeenCalled();
    expect(onSignedOut).not.toHaveBeenCalled();

    later(ACTIVITY_INTERVAL_MS);
    click();
    await settle();
    expect(activity.recordActivity).toHaveBeenCalledTimes(2);
  });

  it("keeps the person signed in when only the refresh fails to get an answer", async () => {
    const onSignedOut = vi.fn();
    activity.recordActivity.mockRejectedValue(new ApiError(401));
    api.refresh.mockRejectedValue(new ApiError(503));
    renderHook(() => useStaffActivity(onSignedOut));

    click();
    await settle();

    expect(onSignedOut).not.toHaveBeenCalled();
  });

  it("only reads the session when the tab becomes visible again; that is not activity", async () => {
    const onSignedOut = vi.fn();
    renderHook(() => useStaffActivity(onSignedOut));

    await becomeVisible();

    expect(api.getSession).toHaveBeenCalledTimes(1);
    expect(activity.recordActivity).not.toHaveBeenCalled();
    expect(onSignedOut).not.toHaveBeenCalled();
  });

  it("does nothing when the tab is hidden", async () => {
    renderHook(() => useStaffActivity(vi.fn()));

    await becomeVisible("hidden");

    expect(api.getSession).not.toHaveBeenCalled();
  });

  it("sends a screen left open to the login when its session ended in the meantime", async () => {
    const onSignedOut = vi.fn();
    api.getSession.mockRejectedValue(new ApiError(401));
    api.refresh.mockRejectedValue(new ApiError(401));
    renderHook(() => useStaffActivity(onSignedOut));

    await becomeVisible();

    expect(onSignedOut).toHaveBeenCalledTimes(1);
    expect(activity.recordActivity).not.toHaveBeenCalled();
  });

  it("signs out a session that is no longer fully signed in", async () => {
    const onSignedOut = vi.fn();
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING" }));
    renderHook(() => useStaffActivity(onSignedOut));

    await becomeVisible();

    expect(onSignedOut).toHaveBeenCalledTimes(1);
  });

  it("uses the newest callback the screen passed", async () => {
    const first = vi.fn();
    const second = vi.fn();
    api.getSession.mockRejectedValue(new ApiError(401));
    api.refresh.mockRejectedValue(new ApiError(401));
    const { rerender } = renderHook(({ callback }: { callback: () => void }) => useStaffActivity(callback), {
      initialProps: { callback: first },
    });

    rerender({ callback: second });
    await becomeVisible();

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("stops listening once the screen is gone", async () => {
    const onSignedOut = vi.fn();
    const { unmount } = renderHook(() => useStaffActivity(onSignedOut));

    unmount();
    click();
    await becomeVisible();

    expect(activity.recordActivity).not.toHaveBeenCalled();
    expect(api.getSession).not.toHaveBeenCalled();
  });

  it("does not call back a screen that went away while a request was still on its way", async () => {
    const onSignedOut = vi.fn();
    let refuse: (error: unknown) => void = () => undefined;
    activity.recordActivity.mockReturnValueOnce(new Promise<void>((_, reject) => (refuse = reject)));
    api.refresh.mockRejectedValue(new ApiError(401));
    const { unmount } = renderHook(() => useStaffActivity(onSignedOut));

    click();
    unmount();
    refuse(new ApiError(401));
    await settle();

    expect(onSignedOut).not.toHaveBeenCalled();
  });
});
