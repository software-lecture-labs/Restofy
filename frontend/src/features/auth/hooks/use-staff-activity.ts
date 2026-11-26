"use client";

import { useEffect, useRef } from "react";
import { ApiError } from "@/shared/lib/api";
import { recordActivity } from "../api/activity-api";
import * as authApi from "../api/auth-api";

/** At most one activity report per minute, however busy the screen is (#64). */
export const ACTIVITY_INTERVAL_MS = 60_000;

/** What counts as the person being there (#64): a click or touch, a key, a scroll. Pointer moves do not. */
const ACTIVITY_EVENTS = ["pointerdown", "keydown", "scroll"] as const;

/** Captured on the document, so a scroll inside any panel counts too (scroll events do not bubble). */
const LISTENER_OPTIONS: AddEventListenerOptions = { capture: true, passive: true };

/** A refused request (not an unreachable service): without a valid cookie Auth answers 401, or 403 from its checks. */
function isRefused(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}

/**
 * Keeps a staff session alive while someone uses the screen, and notices when it has ended.
 *
 * - A click, key or scroll reports activity to Auth, at most once per {@link ACTIVITY_INTERVAL_MS}.
 * - When the tab becomes visible again the session is only read, never extended, so a screen left open notices that
 *   it was signed out in the meantime.
 *
 * The access cookie lives only five minutes, so a refused request is tried once more after a refresh (which does
 * not extend the idle window either). Only when the refresh is refused too is the person signed out: `onSignedOut`
 * runs once and nothing more is sent. An unreachable service changes nothing; the next activity tries again.
 */
export function useStaffActivity(onSignedOut: () => void): void {
  const signedOutCallback = useRef(onSignedOut);
  useEffect(() => {
    signedOutCallback.current = onSignedOut;
  });

  useEffect(() => {
    let mounted = true;
    let signedOut = false;
    let reporting = false;
    let lastReport = Number.NEGATIVE_INFINITY;

    const signOut = () => {
      if (!mounted || signedOut) return;
      signedOut = true;
      signedOutCallback.current();
    };

    async function withRenewal<T>(action: () => Promise<T>): Promise<T | undefined> {
      try {
        return await action();
      } catch (error) {
        if (!isRefused(error)) return undefined;
      }
      try {
        await authApi.refresh();
        return await action();
      } catch (error) {
        if (isRefused(error)) signOut();
        return undefined;
      }
    }

    const report = () => {
      if (signedOut || reporting) return;
      const now = Date.now();
      if (now - lastReport < ACTIVITY_INTERVAL_MS) return;
      lastReport = now;
      reporting = true;
      void withRenewal(recordActivity).finally(() => {
        reporting = false;
      });
    };

    const checkWhenVisible = () => {
      if (signedOut || document.visibilityState !== "visible") return;
      void withRenewal(authApi.getSession).then((session) => {
        if (session && session.status !== "AUTHENTICATED") signOut();
      });
    };

    for (const type of ACTIVITY_EVENTS) document.addEventListener(type, report, LISTENER_OPTIONS);
    document.addEventListener("visibilitychange", checkWhenVisible);
    return () => {
      mounted = false;
      for (const type of ACTIVITY_EVENTS) document.removeEventListener(type, report, LISTENER_OPTIONS);
      document.removeEventListener("visibilitychange", checkWhenVisible);
    };
  }, []);
}
