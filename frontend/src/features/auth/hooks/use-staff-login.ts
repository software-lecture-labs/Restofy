import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/shared/lib/api";
import * as authApi from "../api/auth-api";
import type { StaffRole } from "../config/staff-roles";
import type { LoginValues } from "../model/login-schema";
import type { MfaEnrollment, StaffSession } from "../model/session";

export type LoginStep = "credentials" | "mfa" | "mfaSetup" | "recoveryCodes";

/** Keys under auth.login.problems in the message files. */
export type LoginProblem =
  | "invalidCredentials"
  | "invalidCode"
  | "invalidInput"
  | "tooManyAttempts"
  | "wrongRole"
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
 */
export function useStaffLogin(role: StaffRole) {
  const router = useRouter();
  const [step, setStep] = useState<LoginStep>("credentials");
  const [problem, setProblem] = useState<LoginProblem | null>(null);
  const [pending, setPending] = useState(false);
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);

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
    router.replace(SIGNED_IN_PATH);
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
        setStep("recoveryCodes");
      }, "invalidCode"),
    /** The person has stored the recovery codes; only now do they leave the screen. */
    finish: () => {
      setRecoveryCodes([]);
      router.replace(SIGNED_IN_PATH);
    },
    /** A message about the previous attempt no longer applies once the person picks another role. */
    clearProblem: () => setProblem(null),
    /** Back to the first step; the half-finished session is ended so no stale cookies stay behind. */
    restart: () => endSession(null),
  };
}
