import { useState, type SyntheticEvent } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/shared/components/ui/button";
import { TextField } from "@/shared/components/ui/text-field";
import {
  LOGIN_LIMITS,
  validateLogin,
  type LoginErrors,
  type LoginValues,
} from "../model/login-schema";

type LoginFormProps = {
  /** Called with valid values; the Auth service call itself is made by the caller. */
  onSubmit: (values: LoginValues) => void | Promise<void>;
  pending: boolean;
  /** Already translated message from the server round trip, if any. */
  problem?: string;
};

function EyeIcon({ open }: { open: boolean }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-5"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
      {open ? null : <path d="M4 4l16 16" />}
    </svg>
  );
}

export function LoginForm({ onSubmit, pending, problem }: LoginFormProps) {
  const t = useTranslations("auth.login");
  const [values, setValues] = useState<LoginValues>({ email: "", password: "" });
  const [errors, setErrors] = useState<LoginErrors>({});
  const [passwordVisible, setPasswordVisible] = useState(false);

  function change(field: keyof LoginValues, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  async function submit(event: SyntheticEvent<HTMLFormElement, SubmitEvent>) {
    event.preventDefault();
    const found = validateLogin(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    await onSubmit({ email: values.email.trim(), password: values.password });
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      <TextField
        label={t("email")}
        name="email"
        type="email"
        inputMode="email"
        autoComplete="username"
        spellCheck={false}
        maxLength={LOGIN_LIMITS.email}
        value={values.email}
        onChange={(event) => change("email", event.target.value)}
        error={errors.email ? t(`errors.${errors.email}`) : undefined}
      />
      <TextField
        label={t("password")}
        name="password"
        type={passwordVisible ? "text" : "password"}
        autoComplete="current-password"
        maxLength={LOGIN_LIMITS.password}
        value={values.password}
        onChange={(event) => change("password", event.target.value)}
        error={errors.password ? t(`errors.${errors.password}`) : undefined}
        endAdornment={
          <button
            type="button"
            onClick={() => setPasswordVisible((visible) => !visible)}
            aria-label={passwordVisible ? t("hidePassword") : t("showPassword")}
            aria-pressed={passwordVisible}
            className="grid size-9 place-items-center rounded-full text-fg-muted transition duration-200 hover:text-brand-text focus-visible:outline-2 focus-visible:outline-brand"
          >
            <EyeIcon open={passwordVisible} />
          </button>
        }
      />
      {problem ? (
        <p role="alert" className="text-sm text-danger">
          {problem}
        </p>
      ) : null}
      <Button type="submit" disabled={pending} className="mt-2">
        {t("submit")}
      </Button>
    </form>
  );
}
