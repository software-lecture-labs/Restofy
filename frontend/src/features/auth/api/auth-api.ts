import { apiRequest } from "@/shared/lib/api";
import type { MfaCompletion, MfaEnrollment, StaffSession } from "../model/session";

const BASE = "/api/v1/auth";

export function login(email: string, password: string): Promise<StaffSession> {
  return apiRequest<StaffSession>(`${BASE}/login`, { method: "POST", body: { email, password } });
}

export function getSession(): Promise<StaffSession> {
  return apiRequest<StaffSession>(`${BASE}/session`);
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
