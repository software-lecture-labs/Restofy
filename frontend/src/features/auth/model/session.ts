import type { StaffRole } from "../config/staff-roles";

/** What the Auth service answers for a session: MFA_PENDING means the second factor is still missing. */
export type SessionStatus = "AUTHENTICATED" | "MFA_PENDING";

export type StaffSession = {
  status: SessionStatus;
  role: StaffRole;
  idleExpiresAt: string;
  absoluteExpiresAt: string;
  /** Only present while MFA is pending: the admin has no authenticator yet and must enrol one first. */
  mfaSetupRequired?: boolean;
};

/** A fresh authenticator secret. It is shown once to be typed into the app and never stored by the frontend. */
export type MfaEnrollment = {
  secret: string;
  otpauthUri: string;
  expiresAt: string;
};

/** Confirming the first code activates MFA; the recovery codes are shown once and cannot be fetched again. */
export type MfaCompletion = {
  session: StaffSession;
  recoveryCodes: string[];
};
