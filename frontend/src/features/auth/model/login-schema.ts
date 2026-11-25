/** Limits mirror the Auth service's LoginRequest (email 300, password 72). */
export const LOGIN_LIMITS = { email: 300, password: 72 } as const;

export type LoginValues = { email: string; password: string };
export type LoginErrorKey = "emailRequired" | "emailInvalid" | "passwordRequired";
export type LoginErrors = Partial<Record<keyof LoginValues, LoginErrorKey>>;

/** Domain labels exclude the dot, so the pattern cannot backtrack over where one label ends and the next starts. */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/;

export function validateEmail(value: string): "emailRequired" | "emailInvalid" | undefined {
  const email = value.trim();
  if (email === "") return "emailRequired";
  return EMAIL_PATTERN.test(email) ? undefined : "emailInvalid";
}

/** Cheap client-side checks only; the Auth service stays the authority on credentials. */
export function validateLogin(values: LoginValues): LoginErrors {
  const errors: LoginErrors = {};

  const emailError = validateEmail(values.email);
  if (emailError) errors.email = emailError;

  if (values.password === "") errors.password = "passwordRequired";

  return errors;
}
