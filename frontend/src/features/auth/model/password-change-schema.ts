import { validateNewPassword } from "./reset-schema";

export type PasswordChangeValues = { current: string; password: string; confirmation: string };
export type PasswordChangeErrors = {
  current?: "currentRequired";
  password?: "passwordRequired" | "passwordShort" | "passwordSame";
  confirmation?: "passwordMismatch";
};

/** The new password follows the reset rules and must differ from the current one, as the Auth service demands. */
export function validatePasswordChange(values: PasswordChangeValues): PasswordChangeErrors {
  const errors: PasswordChangeErrors = {};
  if (values.current === "") errors.current = "currentRequired";
  const next = validateNewPassword({ password: values.password, confirmation: values.confirmation });
  if (next.password) errors.password = next.password;
  else if (values.current !== "" && values.password === values.current) errors.password = "passwordSame";
  else if (next.confirmation) errors.confirmation = next.confirmation;
  return errors;
}
