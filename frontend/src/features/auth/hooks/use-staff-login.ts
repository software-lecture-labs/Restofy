import { useState } from "react";
import { useLocale } from "next-intl";
import { useRouter } from "next/navigation";
import { ApiError } from "@/shared/lib/api";
import * as authApi from "../api/auth-api";
import type { StaffRole } from "../config/staff-roles";
import type { LoginValues } from "../model/login-schema";
import type { MfaEnrollment, StaffSession } from "../model/session";

export type LoginStep = "credentials" | "mfa" | "mfaSetup" | "recoveryCodes" | "passwordChange";

/** Keys under auth.login.problems in the message files. */
export type LoginProblem =
  | "invalidCredentials"
  | "invalidCode"
  | "invalidInput"
  | "tooManyAttempts"
  | "wrongRole"
  | "passwordRejected"
  | "expired"
  | "unavailable";

const SIGNED_IN_PATH = "/";

function problemFor(error: unknown, unauthorized: LoginProblem): LoginProblem {
  if (!(error instanceof ApiError)) return "unavailable";
  switch (error.status) {
    case 400:
      return "invalidInput";
    case 401:
      return unauthorized;
    case 429:
      return "tooManyAttempts";
    default:
      return "unavailable";
  }
}

/**
 * The login flow against the Auth service. The role the person picked is only a choice of screen: the account
 * decides the real role, so a session whose role differs from the pick is ended again instead of being used.
 *
 * Admin accounts always need a second factor: a first-time admin enrols an authenticator app (secret, then a
 * confirming code, then one-time recovery codes); afterwards every login asks for the six-digit code.
 *
 * A person who signs in with a password an admin chose (a new account, or a temporary password) must choose their
 * own before the session can be used; for an admin that comes after the second factor.
 */
export function useStaffLogin(role: StaffRole) {
  const router = useRouter();
  const locale = useLocale();
  const [step, setStep] = useState<LoginStep>("credentials");
  const [problem, setProblem] = useState<LoginProblem | null>(null);
  const [pending, setPending] = useState(false);
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [passwordAfterCodes, setPasswordAfterCodes] = useState(false);

  async function endSession(next: LoginProblem | null) {
    await authApi.logout().catch(() => undefined);
    setEnrollment(null);
    setStep("credentials");
    setProblem(next);
  }

  async function handle(session: StaffSession) {
    if (session.role !== role) {
      await endSession("wrongRole");
      return;
    }
    if (session.status === "MFA_PENDING") {
      try {
        // Only GET /session says whether this admin still has to enrol; the login and verify answers never do.
        if ((await authApi.getSession()).mfaSetupRequired) {
          setEnrollment(await authApi.startMfaEnrollment());
          setStep("mfaSetup");
        } else {
          setStep("mfa");
        }
      } catch {
        await endSession("unavailable");
      }
      return;
    }
    if (session.status === "PASSWORD_PENDING") {
      setStep("passwordChange");
      return;
    }
    router.replace(SIGNED_IN_PATH);
  }

  /** 400 is a refused password; 401 or 403 means the short pending session is gone, so the login starts over. */
  async function changePassword(currentPassword: string, newPassword: string) {
    setPending(true);
    setProblem(null);
    try {
      await handle(await authApi.changePassword(currentPassword, newPassword, locale === "en" ? "en" : "tr"));
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) await endSession("expired");
      else setProblem(error instanceof ApiError && error.status === 400 ? "passwordRejected" : problemFor(error, "unavailable"));
    } finally {
      setPending(false);
    }
  }

  async function run(action: () => Promise<void>, unauthorized: LoginProblem) {
    setPending(true);
    setProblem(null);
    try {
      await action();
    } catch (error) {
      setProblem(problemFor(error, unauthorized));
    } finally {
      setPending(false);
    }
  }

  return {
    step,
    problem,
    pending,
    enrollment,
    recoveryCodes,
    submitCredentials: ({ email, password }: LoginValues) =>
      run(async () => handle(await authApi.login(email, password)), "invalidCredentials"),
    submitCode: (code: string) => run(async () => handle(await authApi.verifyTotp(code)), "invalidCode"),
    submitRecoveryCode: (code: string) =>
      run(async () => handle(await authApi.recoverWithCode(code)), "invalidCode"),
    confirmEnrollment: (code: string) =>
      run(async () => {
        const completion = await authApi.confirmMfaEnrollment(code);
        setEnrollment(null);
        setRecoveryCodes(completion.recoveryCodes);
        setPasswordAfterCodes(completion.session.status === "PASSWORD_PENDING");
        setStep("recoveryCodes");
      }, "invalidCode"),
    submitPasswordChange: changePassword,
    /** The person has stored the recovery codes; only now do they leave the screen (or choose their password). */
    finish: () => {
      setRecoveryCodes([]);
      if (passwordAfterCodes) setStep("passwordChange");
      else router.replace(SIGNED_IN_PATH);
    },
    /** A message about the previous attempt no longer applies once the person picks another role. */
    clearProblem: () => setProblem(null),
    /** Back to the first step; the half-finished session is ended so no stale cookies stay behind. */
    restart: () => endSession(null),
  };
}
