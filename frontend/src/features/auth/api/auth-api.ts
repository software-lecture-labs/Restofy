import { apiRequest } from "@/shared/lib/api";
import type { MfaCompletion, MfaEnrollment, StaffSession } from "../model/session";

const BASE = "/api/v1/auth";

export function login(email: string, password: string): Promise<StaffSession> {
  return apiRequest<StaffSession>(`${BASE}/login`, { method: "POST", body: { email, password } });
}

export function getSession(): Promise<StaffSession> {
  return apiRequest<StaffSession>(`${BASE}/session`);
}

let refreshing: Promise<StaffSession> | null = null;

/**
 * Trades the long-lived refresh cookie for a new access cookie; answers with the renewed session. The Auth
 * service rotates the refresh cookie and treats a spent one as stolen once a short grace period has passed, so
 * callers that ask at the same time (React runs effects twice in development) share one request instead of
 * relying on that grace period.
 */
export function refresh(): Promise<StaffSession> {
  refreshing ??= apiRequest<StaffSession>(`${BASE}/refresh`, { method: "POST" }).finally(() => {
    refreshing = null;
  });
  return refreshing;
}

export function verifyTotp(code: string): Promise<StaffSession> {
  return apiRequest<StaffSession>(`${BASE}/mfa/verify`, { method: "POST", body: { code } });
}

export function startMfaEnrollment(): Promise<MfaEnrollment> {
  return apiRequest<MfaEnrollment>(`${BASE}/mfa/enroll`, { method: "POST" });
}

export function confirmMfaEnrollment(code: string): Promise<MfaCompletion> {
  return apiRequest<MfaCompletion>(`${BASE}/mfa/enroll/confirm`, { method: "POST", body: { code } });
}

export function recoverWithCode(code: string): Promise<StaffSession> {
  return apiRequest<StaffSession>(`${BASE}/mfa/recover`, { method: "POST", body: { code } });
}

/** The reset runs on its own cookie (restofy_reset), set by the Auth service and sent back by the browser. */
export function requestPasswordReset(email: string, totpCode: string, locale: string): Promise<void> {
  return apiRequest<void>(`${BASE}/password-reset/request`, { method: "POST", body: { email, totpCode, locale } });
}

export function verifyPasswordResetCode(code: string): Promise<void> {
  return apiRequest<void>(`${BASE}/password-reset/verify`, { method: "POST", body: { code } });
}

export function confirmPasswordReset(newPassword: string): Promise<void> {
  return apiRequest<void>(`${BASE}/password-reset/confirm`, { method: "POST", body: { newPassword } });
}

export function logout(): Promise<void> {
  return apiRequest<void>(`${BASE}/logout`, { method: "POST" });
}
