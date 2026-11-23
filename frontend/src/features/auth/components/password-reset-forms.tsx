import { useState, type ReactNode, type SyntheticEvent } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";
import { TextField } from "@/shared/components/ui/text-field";
import { validateMfaCode } from "../model/mfa-code";
import {
  RESET_LIMITS,
  validateNewPassword,
  validateResetRequest,
  type NewPasswordErrors,
  type ResetRequestErrors,
} from "../model/reset-schema";

type StepFormProps = {
  pending: boolean;
  /** Already translated message from the server round trip, if any. */
  problem?: string;
};

function FormFrame({
  onSubmit,
  problem,
  pending,
  submitLabel,
  children,
}: StepFormProps & {
  onSubmit: (event: SyntheticEvent<HTMLFormElement, SubmitEvent>) => void;
  submitLabel: string;
  children: ReactNode;
}) {
  return (
    <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
      {children}
      {problem ? (
        <p role="alert" className="text-sm text-danger">
          {problem}
        </p>
      ) : null}
      <Button type="submit" disabled={pending} className="mt-2">
        {submitLabel}
      </Button>
    </form>
  );
}

export function ResetRequestForm({
  onSubmit,
  pending,
  problem,
}: StepFormProps & { onSubmit: (email: string, totpCode: string) => void | Promise<void> }) {
  const t = useTranslations("auth.reset");
  const tLogin = useTranslations("auth.login");
  const [values, setValues] = useState({ email: "", totpCode: "" });
  const [errors, setErrors] = useState<ResetRequestErrors>({});

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validateResetRequest(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    await onSubmit(values.email.trim(), values.totpCode.trim());
  }

  return (
    <FormFrame onSubmit={submit} pending={pending} problem={problem} submitLabel={t("request.submit")}>
      <TextField
        label={tLogin("email")}
        name="email"
        type="email"
        inputMode="email"
        autoComplete="username"
        spellCheck={false}
        autoFocus
        maxLength={RESET_LIMITS.email}
        value={values.email}
        onChange={(event) => {
          setValues((current) => ({ ...current, email: event.target.value }));
          setErrors((current) => ({ ...current, email: undefined }));
        }}
        error={errors.email ? tLogin(`errors.${errors.email}`) : undefined}
      />
      <TextField
        label={t("request.totp")}
        name="totpCode"
        autoComplete="one-time-code"
        inputMode="numeric"
        spellCheck={false}
        maxLength={6}
        value={values.totpCode}
        onChange={(event) => {
          setValues((current) => ({ ...current, totpCode: event.target.value }));
          setErrors((current) => ({ ...current, totpCode: undefined }));
        }}
        error={errors.totpCode ? t(`errors.${errors.totpCode}`) : undefined}
      />
    </FormFrame>
  );
}

export function ResetCodeForm({
  onSubmit,
  pending,
  problem,
}: StepFormProps & { onSubmit: (code: string) => void | Promise<void> }) {
  const t = useTranslations("auth.reset");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validateMfaCode("totp", code);
    setError(found ? t(`errors.${found}`) : undefined);
    if (found) return;
    await onSubmit(code.trim());
  }

  return (
    <FormFrame onSubmit={submit} pending={pending} problem={problem} submitLabel={t("code.submit")}>
      <TextField
        label={t("code.label")}
        name="code"
        autoComplete="one-time-code"
        inputMode="numeric"
        spellCheck={false}
        autoFocus
        maxLength={6}
        value={code}
        onChange={(event) => {
          setCode(event.target.value);
          setError(undefined);
        }}
        error={error}
      />
    </FormFrame>
  );
}

export function NewPasswordForm({
  onSubmit,
  pending,
  problem,
}: StepFormProps & { onSubmit: (password: string) => void | Promise<void> }) {
  const t = useTranslations("auth.reset");
  const [values, setValues] = useState({ password: "", confirmation: "" });
  const [errors, setErrors] = useState<NewPasswordErrors>({});

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validateNewPassword(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    await onSubmit(values.password);
  }

  return (
    <FormFrame onSubmit={submit} pending={pending} problem={problem} submitLabel={t("password.submit")}>
      <TextField
        label={t("password.label")}
        name="newPassword"
        type="password"
        autoComplete="new-password"
        autoFocus
        maxLength={RESET_LIMITS.password}
        value={values.password}
        onChange={(event) => {
          setValues((current) => ({ ...current, password: event.target.value }));
          setErrors((current) => ({ ...current, password: undefined }));
        }}
        error={errors.password ? t(`errors.${errors.password}`) : undefined}
      />
      <TextField
        label={t("password.confirmation")}
        name="confirmation"
        type="password"
        autoComplete="new-password"
        maxLength={RESET_LIMITS.password}
        value={values.confirmation}
        onChange={(event) => {
          setValues((current) => ({ ...current, confirmation: event.target.value }));
          setErrors((current) => ({ ...current, confirmation: undefined }));
        }}
        error={errors.confirmation ? t(`errors.${errors.confirmation}`) : undefined}
      />
    </FormFrame>
  );
}
