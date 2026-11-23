import { validateEmail } from "./login-schema";
import { validateMfaCode } from "./mfa-code";

/** Limits mirror the Auth service's password rules: at least 12 characters, at most 72. */
export const RESET_LIMITS = { email: 300, password: 72, minPassword: 12 } as const;

export type ResetRequestValues = { email: string; totpCode: string };
export type ResetRequestErrors = {
  email?: "emailRequired" | "emailInvalid";
  totpCode?: "codeRequired" | "codeInvalid";
};

export function validateResetRequest(values: ResetRequestValues): ResetRequestErrors {
  const errors: ResetRequestErrors = {};
  const email = validateEmail(values.email);
  if (email) errors.email = email;
  const totpCode = validateMfaCode("totp", values.totpCode);
  if (totpCode) errors.totpCode = totpCode;
  return errors;
}

export type NewPasswordValues = { password: string; confirmation: string };
export type NewPasswordErrors = {
  password?: "passwordRequired" | "passwordShort";
  confirmation?: "passwordMismatch";
};

export function validateNewPassword(values: NewPasswordValues): NewPasswordErrors {
  const errors: NewPasswordErrors = {};
  if (values.password === "") errors.password = "passwordRequired";
  else if ([...values.password].length < RESET_LIMITS.minPassword) errors.password = "passwordShort";
  if (!errors.password && values.confirmation !== values.password) errors.confirmation = "passwordMismatch";
  return errors;
}
