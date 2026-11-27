import { useState, type SyntheticEvent } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";
import { TextField } from "@/shared/components/ui/text-field";
import {
  validatePasswordChange,
  type PasswordChangeErrors,
  type PasswordChangeValues,
} from "../model/password-change-schema";
import { RESET_LIMITS } from "../model/reset-schema";

type PasswordChangeFormProps = Readonly<{
  onSubmit: (currentPassword: string, newPassword: string) => void | Promise<void>;
  pending: boolean;
  /** Already translated message from the server round trip, if any. */
  problem?: string;
  /** Shown as a secondary action when given: back to the login, or closing the form. */
  onCancel?: () => void;
  cancelLabel?: string;
}>;

const EMPTY: PasswordChangeValues = { current: "", password: "", confirmation: "" };

/** The current password, then the new one twice. Used for the forced change at sign-in and on the home page. */
export function PasswordChangeForm({ onSubmit, pending, problem, onCancel, cancelLabel }: PasswordChangeFormProps) {
  const t = useTranslations("auth.passwordChange");
  const [values, setValues] = useState<PasswordChangeValues>(EMPTY);
  const [errors, setErrors] = useState<PasswordChangeErrors>({});

  function field(name: keyof PasswordChangeValues) {
    return {
      value: values[name],
      maxLength: RESET_LIMITS.password,
      type: "password" as const,
      error: errors[name] ? t(`errors.${errors[name]}`) : undefined,
      onChange: (event: { target: { value: string } }) => {
        setValues((current) => ({ ...current, [name]: event.target.value }));
        setErrors((current) => ({ ...current, [name]: undefined }));
      },
    };
  }

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validatePasswordChange(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    await onSubmit(values.current, values.password);
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      <TextField label={t("current")} name="currentPassword" autoComplete="current-password" autoFocus {...field("current")} />
      <TextField label={t("password")} name="newPassword" autoComplete="new-password" {...field("password")} />
      <TextField label={t("confirmation")} name="confirmation" autoComplete="new-password" {...field("confirmation")} />
      {problem ? (
        <p role="alert" className="text-sm text-danger">
          {problem}
        </p>
      ) : null}
      <div className="mt-2 flex items-center justify-between gap-3">
        <Button type="submit" disabled={pending}>
          {t("submit")}
        </Button>
        {onCancel ? (
          <button
            type="button"
            onClick={onCancel}
            disabled={pending}
            className="text-sm text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-brand"
          >
            {cancelLabel}
          </button>
        ) : null}
      </div>
    </form>
  );
}
