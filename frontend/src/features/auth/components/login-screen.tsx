"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { defaultStaffRole, staffRoles, type StaffRole } from "../config/staff-roles";
import { useStaffLogin } from "../hooks/use-staff-login";
import { AuthShell } from "./auth-shell";
import { LoginForm } from "./login-form";
import { MfaForm } from "./mfa-form";
import { MfaSetup } from "./mfa-setup";
import { RecoveryCodes } from "./recovery-codes";
import { RoleIcon } from "./role-icon";
import { ROLE_PANEL_ID, RoleSwitcher, roleTabId } from "./role-switcher";

/** Heading and hint (keys under auth.mfa) of each step that comes after the credentials. */
const MFA_COPY = {
  mfa: { heading: "heading", hint: undefined as undefined },
  mfaSetup: { heading: "setupHeading", hint: "setupHint" },
  recoveryCodes: { heading: "recoveryCodesHeading", hint: "recoveryCodesHint" },
} as const;

type Selection = { role: StaffRole; direction?: "next" | "prev" };

/**
 * The only client boundary of the login. A Server Component page cannot pass it functions, so the calls to the
 * Auth service are made here, through a hook of this feature, not handed in from outside.
 */
export function LoginScreen() {
  const t = useTranslations("auth.login");
  const tMfa = useTranslations("auth.mfa");
  const [selection, setSelection] = useState<Selection>({ role: defaultStaffRole });

  function selectRole(role: StaffRole) {
    login.clearProblem();
    setSelection((current) =>
      role === current.role
        ? current
        : {
            role,
            direction:
              staffRoles.indexOf(role) > staffRoles.indexOf(current.role) ? "next" : "prev",
          },
    );
  }

  const { role, direction } = selection;
  const login = useStaffLogin(role);
  const problem = login.problem ? t(`problems.${login.problem}`) : undefined;
  // The "mfa" step has no hint here: its form shows one per code kind.
  const hintKey = login.step === "credentials" ? undefined : MFA_COPY[login.step].hint;

  return (
    <AuthShell>
          {login.step === "credentials" ? (
            <>
              <RoleSwitcher value={role} onChange={selectRole} />

              <div
                role="tabpanel"
                id={ROLE_PANEL_ID}
                aria-labelledby={roleTabId(role)}
                className="mt-8 flex flex-col gap-6"
              >
                <div key={role} data-direction={direction} className="login-slide flex items-start gap-4">
                  <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-brand/15 text-brand-text">
                    <RoleIcon role={role} className="size-6" />
                  </span>
                  <div>
                    <h1 className="text-xl font-semibold tracking-tight text-fg">
                      {t(`heading.${role}`)}
                    </h1>
                    <p className="mt-1 text-sm leading-relaxed text-fg-muted">{t(`hint.${role}`)}</p>
                  </div>
                </div>

                <LoginForm onSubmit={login.submitCredentials} pending={login.pending} problem={problem} />
                {/* Only admins can reset their password by e-mail; the Auth service refuses every other role. */}
                {role === "ADMIN" ? (
                  <Link
                    href="/forgot-password"
                    className="-mt-2 self-center text-sm font-medium text-brand-text hover:underline focus-visible:outline-2 focus-visible:outline-brand"
                  >
                    {t("forgot")}
                  </Link>
                ) : null}
              </div>
            </>
          ) : (
            <div className="flex flex-col gap-6">
              <div>
                <h1 className="text-xl font-semibold tracking-tight text-fg">
                  {tMfa(MFA_COPY[login.step].heading)}
                </h1>
                {hintKey ? (
                  <p className="mt-1 text-sm leading-relaxed text-fg-muted">{tMfa(hintKey)}</p>
                ) : null}
              </div>
              {login.step === "mfa" ? (
                <MfaForm
                  onVerify={login.submitCode}
                  onRecover={login.submitRecoveryCode}
                  onBack={login.restart}
                  pending={login.pending}
                  problem={problem}
                />
              ) : null}
              {login.step === "mfaSetup" && login.enrollment ? (
                <MfaSetup
                  enrollment={login.enrollment}
                  onConfirm={login.confirmEnrollment}
                  onBack={login.restart}
                  pending={login.pending}
                  problem={problem}
                />
              ) : null}
              {login.step === "recoveryCodes" ? (
                <RecoveryCodes codes={login.recoveryCodes} onDone={login.finish} />
              ) : null}
            </div>
          )}
    </AuthShell>
  );
}
