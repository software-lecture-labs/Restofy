export type MfaCodeKind = "totp" | "recovery";
export type MfaCodeError = "codeRequired" | "codeInvalid";

/** The same shapes the Auth service accepts: six digits, or a recovery code of 20 characters (dashes allowed). */
const TOTP = /^\d{6}$/;
const RECOVERY = /^([a-z2-7]{20}|[a-z2-7]{4}(-[a-z2-7]{4}){4})$/i;

export function validateMfaCode(kind: MfaCodeKind, value: string): MfaCodeError | undefined {
  const code = value.trim();
  if (code === "") return "codeRequired";
  return (kind === "totp" ? TOTP : RECOVERY).test(code) ? undefined : "codeInvalid";
}
