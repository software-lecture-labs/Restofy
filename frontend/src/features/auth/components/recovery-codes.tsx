import { useState } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";

type RecoveryCodesProps = {
  codes: readonly string[];
  onDone: () => void;
};

/** Shown exactly once after MFA is activated: the Auth service cannot show these codes again. */
export function RecoveryCodes({ codes, onDone }: RecoveryCodesProps) {
  const t = useTranslations("auth.mfa.recoveryCodes");
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(codes.join("\n"));
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <ul className="flex flex-col gap-2">
        {codes.map((code) => (
          <li
            key={code}
            className="select-all rounded-xl border border-line bg-surface-muted px-3 py-2 text-center font-mono text-sm font-semibold text-fg"
          >
            {code}
          </li>
        ))}
      </ul>
      <button
        type="button"
        onClick={copy}
        className="self-start text-sm font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand"
      >
        {copied ? t("copied") : t("copy")}
      </button>
      <Button type="button" onClick={onDone}>
        {t("done")}
      </Button>
    </div>
  );
}
