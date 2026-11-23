import { useState, type SyntheticEvent } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";
import { TextField } from "@/shared/components/ui/text-field";
import { validateMfaCode } from "../model/mfa-code";
import type { MfaEnrollment } from "../model/session";
import { OtpauthQr } from "./otpauth-qr";

type MfaSetupProps = {
  enrollment: MfaEnrollment;
  onConfirm: (code: string) => void | Promise<void>;
  onBack: () => void;
  pending: boolean;
  problem?: string;
};

/** The secret is shown in groups of four so it is easy to type into an authenticator app by hand. */
function groupSecret(secret: string): string {
  return secret.replace(/\s+/g, "").replace(/(.{4})/g, "$1 ").trim();
}

/** Only an otpauth:// address may become a link; anything else the server might send is ignored. */
function safeOtpauthLink(uri: string): string | undefined {
  return uri.startsWith("otpauth://") ? uri : undefined;
}

export function MfaSetup({ enrollment, onConfirm, onBack, pending, problem }: MfaSetupProps) {
  const t = useTranslations("auth.mfa");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();
  const link = safeOtpauthLink(enrollment.otpauthUri);

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validateMfaCode("totp", code);
    setError(found ? t(`errors.${found}`) : undefined);
    if (found) return;
    await onConfirm(code.trim());
  }

  return (
    <div className="flex flex-col gap-5">
      <ol className="flex flex-col gap-4 text-sm leading-relaxed text-fg-muted">
        <li>
          <span className="font-semibold text-fg">1. </span>
          {t("setup.stepApp")}
        </li>
        <li>
          <span className="font-semibold text-fg">2. </span>
          {t("setup.stepScan")}
          {link ? <OtpauthQr value={link} label={t("setup.qrLabel")} /> : null}
          <span className="mt-3 block">{t("setup.stepSecret")}</span>
          <span
            data-testid="mfa-secret"
            className="mt-2 block select-all rounded-2xl border border-line bg-surface-muted px-4 py-3 text-center font-mono text-base font-semibold tracking-wider text-fg"
          >
            {groupSecret(enrollment.secret)}
          </span>
          {link ? (
            <a href={link} className="mt-2 inline-block font-medium text-brand-text hover:underline">
              {t("setup.openInApp")}
            </a>
          ) : null}
        </li>
        <li>
          <span className="font-semibold text-fg">3. </span>
          {t("setup.stepCode")}
        </li>
      </ol>

      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        <TextField
          label={t("totp.label")}
          name="code"
          autoComplete="one-time-code"
          inputMode="numeric"
          spellCheck={false}
          maxLength={6}
          value={code}
          onChange={(event) => {
            setCode(event.target.value);
            setError(undefined);
          }}
          error={error}
        />
        {problem ? (
          <p role="alert" className="text-sm text-danger">
            {problem}
          </p>
        ) : null}
        <Button type="submit" disabled={pending}>
          {t("setup.submit")}
        </Button>
        <button
          type="button"
          onClick={onBack}
          className="self-start text-sm text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-brand"
        >
          {t("back")}
        </button>
      </form>
    </div>
  );
}
