import { useState, type SyntheticEvent } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";
import { TextField } from "@/shared/components/ui/text-field";
import { ApiError } from "@/shared/lib/api";
import * as authApi from "../api/auth-api";
import { validateMfaCode, type MfaCodeKind } from "../model/mfa-code";
import { RecoveryCodes } from "./recovery-codes";

/** Keys under auth.home.recovery.problems in the message files. */
type RenewalProblem = "invalidCredentials" | "invalidInput" | "tooManyAttempts" | "unavailable";

function problemFor(error: unknown): RenewalProblem {
  if (!(error instanceof ApiError)) return "unavailable";
  switch (error.status) {
    case 400:
      return "invalidInput";
    case 401:
      return "invalidCredentials";
    case 429:
      return "tooManyAttempts";
    default:
      return "unavailable";
  }
}

function isSignedOut(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}

type RecoveryRenewalProps = Readonly<{
  /** Called when the session turns out to be gone, so the page can send the person to the login. */
  onSignedOut: () => void;
}>;

/**
 * Lets an admin trade the current recovery codes for new ones. The Auth service asks for the password and exactly
 * one second factor, ends the admin's other sessions and shows the new codes only in this answer.
 */
export function RecoveryRenewal({ onSignedOut }: RecoveryRenewalProps) {
  const t = useTranslations("auth.home.recovery");
  const tMfa = useTranslations("auth.mfa");
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<MfaCodeKind>("totp");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [errors, setErrors] = useState<{ password?: string; code?: string }>({});
  const [problem, setProblem] = useState<RenewalProblem | null>(null);
  const [pending, setPending] = useState(false);
  const [codes, setCodes] = useState<string[] | null>(null);

  function reset() {
    setOpen(false);
    setKind("totp");
    setPassword("");
    setCode("");
    setErrors({});
    setProblem(null);
    setCodes(null);
  }

  function switchKind() {
    setKind((current) => (current === "totp" ? "recovery" : "totp"));
    setCode("");
    setErrors((current) => ({ ...current, code: undefined }));
  }

  /**
   * The access cookie lives only a few minutes, and a missing one would come back as the same 401 as a wrong
   * password. Reading the session first (and renewing it if needed) keeps those two apart without spending a guess.
   */
  async function ensureSignedIn(): Promise<boolean> {
    try {
      await authApi.getSession().catch((error: unknown) => {
        if (isSignedOut(error)) return authApi.refresh();
        throw error;
      });
      return true;
    } catch (error) {
      if (isSignedOut(error)) {
        onSignedOut();
        return false;
      }
      throw error;
    }
  }

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const codeError = validateMfaCode(kind, code);
    const found = {
      password: password === "" ? t("errors.passwordRequired") : undefined,
      code: codeError ? tMfa(`errors.${codeError}`) : undefined,
    };
    setErrors(found);
    setProblem(null);
    if (found.password || found.code) return;

    setPending(true);
    try {
      if (!(await ensureSignedIn())) return;
      const trimmed = code.trim();
      const factor = kind === "totp" ? { totpCode: trimmed } : { recoveryCode: trimmed };
      setCodes(await authApi.regenerateRecoveryCodes(password, factor));
      setPassword("");
      setCode("");
    } catch (error) {
      setProblem(problemFor(error));
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="flex flex-col gap-3 border-t border-line pt-5" aria-labelledby="recovery-renewal-heading">
      <h2 id="recovery-renewal-heading" className="text-lg font-semibold text-fg">
        {t("heading")}
      </h2>

      {codes ? (
        <>
          <p className="text-sm leading-relaxed text-fg-muted">{t("doneHint")}</p>
          <RecoveryCodes codes={codes} onDone={reset} />
        </>
      ) : null}

      {!codes && !open ? (
        <>
          <p className="text-sm leading-relaxed text-fg-muted">{t("hint")}</p>
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="self-start text-sm font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand"
          >
            {t("open")}
          </button>
        </>
      ) : null}

      {!codes && open ? (
        <form noValidate onSubmit={submit} className="flex flex-col gap-4">
          <p className="text-sm leading-relaxed text-fg-muted">{t("hint")}</p>
          <TextField
            label={t("password")}
            name="password"
            type="password"
            autoComplete="current-password"
            maxLength={72}
            value={password}
            onChange={(event) => {
              setPassword(event.target.value);
              setErrors((current) => ({ ...current, password: undefined }));
            }}
            error={errors.password}
          />
          <TextField
            label={tMfa(`${kind}.label`)}
            name="code"
            autoComplete="one-time-code"
            inputMode={kind === "totp" ? "numeric" : "text"}
            spellCheck={false}
            maxLength={kind === "totp" ? 6 : 24}
            value={code}
            onChange={(event) => {
              setCode(event.target.value);
              setErrors((current) => ({ ...current, code: undefined }));
            }}
            error={errors.code}
          />
          <button
            type="button"
            onClick={switchKind}
            className="self-start text-sm font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand"
          >
            {tMfa(`${kind}.switch`)}
          </button>
          {problem ? (
            <p role="alert" className="text-sm text-danger">
              {t(`problems.${problem}`)}
            </p>
          ) : null}
          <div className="flex items-center justify-between gap-3">
            <Button type="submit" disabled={pending}>
              {t("submit")}
            </Button>
            <button
              type="button"
              onClick={reset}
              disabled={pending}
              className="text-sm text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-brand"
            >
              {t("cancel")}
            </button>
          </div>
        </form>
      ) : null}
    </section>
  );
}
