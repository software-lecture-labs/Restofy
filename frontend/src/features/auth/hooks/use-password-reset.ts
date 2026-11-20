import { useState } from "react";
import { useLocale } from "next-intl";
import { ApiError } from "@/shared/lib/api";
import * as authApi from "../api/auth-api";

export type ResetStep = "request" | "code" | "password" | "done";

/** Keys under auth.reset.problems in the message files. */
export type ResetProblem =
  | "invalidRequest"
  | "invalidCode"
  | "invalidInput"
  | "weakPassword"
  | "expired"
  | "tooManyAttempts"
  | "unavailable";

/** What a 400 and a 401 mean differs per step, so each step names its own. */
type StepProblems = { badInput: ResetProblem; unauthorized: ResetProblem };

const PROBLEMS: Record<Exclude<ResetStep, "done">, StepProblems> = {
  request: { badInput: "invalidInput", unauthorized: "invalidRequest" },
  code: { badInput: "invalidInput", unauthorized: "invalidCode" },
  password: { badInput: "weakPassword", unauthorized: "expired" },
};

function problemFor(error: unknown, step: Exclude<ResetStep, "done">): ResetProblem {
  if (!(error instanceof ApiError)) return "unavailable";
  switch (error.status) {
    case 400:
      return PROBLEMS[step].badInput;
    case 401:
      return PROBLEMS[step].unauthorized;
    case 429:
      return "tooManyAttempts";
    default:
      return "unavailable";
  }
}

/**
 * The admin password reset against the Auth service: the e-mail plus the authenticator code start it, the code
 * that arrives by e-mail proves the mailbox, and only then a new password is accepted. The Auth service keeps
 * the state of the request in its own cookie; this code never sees or stores it.
 */
export function usePasswordReset() {
  const locale = useLocale();
  const [step, setStep] = useState<ResetStep>("request");
  const [problem, setProblem] = useState<ResetProblem | null>(null);
  const [pending, setPending] = useState(false);

  async function run(from: Exclude<ResetStep, "done">, to: ResetStep, action: () => Promise<void>) {
    setPending(true);
    setProblem(null);
    try {
      await action();
      setStep(to);
    } catch (error) {
      const next = problemFor(error, from);
      setProblem(next);
      // A password step that expired cannot be retried with the same request: start over.
      if (next === "expired") setStep("request");
    } finally {
      setPending(false);
    }
  }

  return {
    step,
    problem,
    pending,
    submitRequest: (email: string, totpCode: string) =>
      run("request", "code", () => authApi.requestPasswordReset(email, totpCode, locale === "en" ? "en" : "tr")),
    submitCode: (code: string) => run("code", "password", () => authApi.verifyPasswordResetCode(code)),
    submitPassword: (password: string) => run("password", "done", () => authApi.confirmPasswordReset(password)),
    /** Back to the first step; the e-mailed code of the old request is simply never used. */
    restart: () => {
      setProblem(null);
      setStep("request");
    },
  };
}
