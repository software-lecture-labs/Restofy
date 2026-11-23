import { useState, type SyntheticEvent } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";
import { TextField } from "@/shared/components/ui/text-field";
import { validateMfaCode, type MfaCodeKind } from "../model/mfa-code";

type MfaFormProps = {
  onVerify: (code: string) => void | Promise<void>;
  onRecover: (code: string) => void | Promise<void>;
  onBack: () => void;
  pending: boolean;
  /** Already translated message from the server round trip, if any. */
  problem?: string;
};

export function MfaForm({ onVerify, onRecover, onBack, pending, problem }: MfaFormProps) {
  const t = useTranslations("auth.mfa");
  const [kind, setKind] = useState<MfaCodeKind>("totp");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();

  function switchKind() {
    setKind((current) => (current === "totp" ? "recovery" : "totp"));
    setCode("");
    setError(undefined);
  }

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validateMfaCode(kind, code);
    setError(found ? t(`errors.${found}`) : undefined);
    if (found) return;
    await (kind === "totp" ? onVerify(code.trim()) : onRecover(code.trim()));
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      <p className="-mt-3 text-sm leading-relaxed text-fg-muted">{t(`${kind}.hint`)}</p>
      <TextField
        label={t(`${kind}.label`)}
        name="code"
        autoComplete="one-time-code"
        inputMode={kind === "totp" ? "numeric" : "text"}
        spellCheck={false}
        autoFocus
        maxLength={kind === "totp" ? 6 : 24}
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
      <Button type="submit" disabled={pending} className="mt-2">
        {t("submit")}
      </Button>
      <div className="flex items-center justify-between text-sm">
        <button type="button" onClick={onBack} className="text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-brand">
          {t("back")}
        </button>
        <button type="button" onClick={switchKind} className="font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand">
          {t(`${kind}.switch`)}
        </button>
      </div>
    </form>
  );
}
