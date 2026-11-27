import { apiRequest } from "@/shared/lib/api";

/**
 * Tells the Auth service that the person is still at the screen, which moves the end of the idle window (15 minutes,
 * 60 for the kitchen). Nothing else extends it: reading the session or refreshing the cookie never does.
 */
export function recordActivity(): Promise<void> {
  return apiRequest("/api/v1/auth/activity", { method: "POST" });
}
