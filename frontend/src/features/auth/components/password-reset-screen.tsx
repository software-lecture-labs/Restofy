"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { usePasswordReset } from "../hooks/use-password-reset";
import { AuthShell } from "./auth-shell";
import { NewPasswordForm, ResetCodeForm, ResetRequestForm } from "./password-reset-forms";

const backLink =
  "text-sm font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand";

/** The admin "forgot password" flow: e-mail and authenticator code, e-mailed code, then the new password. */
export function PasswordResetScreen() {
  const t = useTranslations("auth.reset");
  const reset = usePasswordReset();
  const problem = reset.problem ? t(`problems.${reset.problem}`) : undefined;

  return (
    <AuthShell>
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-fg">{t(`${reset.step}.heading`)}</h1>
          <p className="mt-1 text-sm leading-relaxed text-fg-muted">{t(`${reset.step}.hint`)}</p>
        </div>

        {reset.step === "request" ? (
          <ResetRequestForm onSubmit={reset.submitRequest} pending={reset.pending} problem={problem} />
        ) : null}
        {reset.step === "code" ? (
          <ResetCodeForm onSubmit={reset.submitCode} pending={reset.pending} problem={problem} />
        ) : null}
        {reset.step === "password" ? (
          <NewPasswordForm onSubmit={reset.submitPassword} pending={reset.pending} problem={problem} />
        ) : null}

        <div className="flex items-center justify-between">
          <Link href="/login" className={reset.step === "done" ? backLink : "text-sm text-fg-muted hover:text-fg"}>
            {t("backToLogin")}
          </Link>
          {reset.step === "code" || reset.step === "password" ? (
            <button type="button" onClick={reset.restart} className={backLink}>
              {t("startOver")}
            </button>
          ) : null}
        </div>
      </div>
    </AuthShell>
  );
}
