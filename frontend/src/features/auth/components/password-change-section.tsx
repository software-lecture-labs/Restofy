import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { ApiError } from "@/shared/lib/api";
import * as authApi from "../api/auth-api";
import { PasswordChangeForm } from "./password-change-form";

/** Keys under auth.passwordChange.problems in the message files. */
type ChangeProblem = "rejected" | "tooManyAttempts" | "unavailable";

function isSignedOut(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}

type PasswordChangeSectionProps = Readonly<{
  /** Called when the session turns out to be gone, so the page can send the person to the login. */
  onSignedOut: () => void;
}>;

/** Any signed-in person replaces their own password here. This session stays open; the others end. */
export function PasswordChangeSection({ onSignedOut }: PasswordChangeSectionProps) {
  const t = useTranslations("auth.passwordChange");
  const locale = useLocale();
  const [open, setOpen] = useState(false);
  const [done, setDone] = useState(false);
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<ChangeProblem | null>(null);

  function close() {
    setOpen(false);
    setProblem(null);
  }

  async function submit(currentPassword: string, newPassword: string) {
    setPending(true);
    setProblem(null);
    try {
      // The access cookie lives only a few minutes: renew it first, so a 401 below really means signed out.
      await authApi.getSession().catch((error: unknown) => {
        if (isSignedOut(error)) return authApi.refresh();
        throw error;
      });
      await authApi.changePassword(currentPassword, newPassword, locale === "en" ? "en" : "tr");
      setOpen(false);
      setDone(true);
    } catch (error) {
      if (isSignedOut(error)) onSignedOut();
      else if (error instanceof ApiError && error.status === 400) setProblem("rejected");
      else if (error instanceof ApiError && error.status === 429) setProblem("tooManyAttempts");
      else setProblem("unavailable");
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="flex flex-col gap-3 border-t border-line pt-5" aria-labelledby="password-change-heading">
      <h2 id="password-change-heading" className="text-lg font-semibold text-fg">
        {t("homeHeading")}
      </h2>
      {done ? (
        <p role="status" className="text-sm leading-relaxed text-fg-muted">
          {t("done")}
        </p>
      ) : null}
      {open ? (
        <>
          <p className="text-sm leading-relaxed text-fg-muted">{t("homeHint")}</p>
          <PasswordChangeForm
            onSubmit={submit}
            onCancel={close}
            cancelLabel={t("cancel")}
            pending={pending}
            problem={problem ? t(`problems.${problem}`) : undefined}
          />
        </>
      ) : (
        <button
          type="button"
          onClick={() => {
            setDone(false);
            setOpen(true);
          }}
          className="self-start text-sm font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand"
        >
          {t("open")}
        </button>
      )}
    </section>
  );
}
