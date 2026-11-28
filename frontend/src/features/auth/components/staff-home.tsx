"use client";

import { useEffect, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { Button } from "@/shared/components/ui/button";
import { ApiError } from "@/shared/lib/api";
import * as authApi from "../api/auth-api";
import type { StaffSession } from "../model/session";
import { AuthShell } from "./auth-shell";
import { PasswordChangeSection } from "./password-change-section";
import { RecoveryRenewal } from "./recovery-renewal";

const LOGIN_PATH = "/login";

/** A refused request (not an unreachable service): without a valid cookie Auth answers 401, or 403 from its CSRF check. */
function isSignedOut(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}

type State =
  | { kind: "loading" }
  | { kind: "ready"; session: StaffSession }
  | { kind: "unavailable" };

/**
 * The page a signed-in person lands on. It asks the Auth service for the session instead of trusting anything
 * the browser holds: no session (or one still waiting for its second factor) sends the person back to the login.
 * It only proves the sign-in works and offers the sign-out; the role panels come with their own features.
 */
export function StaffHome() {
  const t = useTranslations("auth.home");
  const tRoles = useTranslations("auth.roles");
  const format = useFormatter();
  const router = useRouter();
  const [state, setState] = useState<State>({ kind: "loading" });
  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    let active = true;
    // The access cookie lives only a few minutes while the session itself lasts longer, so an answer of 401 or 403 first
    // tries the refresh cookie; only when that is refused as well is the person really signed out.
    authApi
      .getSession()
      .catch((error: unknown) => {
        if (isSignedOut(error)) return authApi.refresh();
        throw error;
      })
      .then((session) => {
        if (!active) return;
        if (session.status !== "AUTHENTICATED") router.replace(LOGIN_PATH);
        else setState({ kind: "ready", session });
      })
      .catch((error: unknown) => {
        if (!active) return;
        if (isSignedOut(error)) router.replace(LOGIN_PATH);
        else setState({ kind: "unavailable" });
      });
    return () => {
      active = false;
    };
  }, [router]);

  async function signOut() {
    setSigningOut(true);
    // The login screen is the right place to land even when the Auth service cannot be reached just now.
    await authApi.logout().catch(() => undefined);
    router.replace(LOGIN_PATH);
  }

  const when = (iso: string) => format.dateTime(new Date(iso), { dateStyle: "medium", timeStyle: "short" });

  return (
    <AuthShell>
      <div className="flex flex-col gap-5" aria-live="polite">
        <h1 className="text-2xl font-semibold text-fg">{t("heading")}</h1>

        {state.kind === "loading" ? <p className="text-fg-muted">{t("loading")}</p> : null}

        {state.kind === "unavailable" ? (
          <p role="alert" className="text-sm text-danger">
            {t("problems.unavailable")}
          </p>
        ) : null}

        {state.kind === "ready" ? (
          <>
            <dl className="flex flex-col gap-3 text-sm">
              <div>
                <dt className="text-fg-muted">{t("role")}</dt>
                <dd className="font-medium text-fg">{tRoles(state.session.role)}</dd>
              </div>
              <div>
                <dt className="text-fg-muted">{t("idleExpiresAt")}</dt>
                <dd className="font-medium text-fg">{when(state.session.idleExpiresAt)}</dd>
              </div>
              <div>
                <dt className="text-fg-muted">{t("absoluteExpiresAt")}</dt>
                <dd className="font-medium text-fg">{when(state.session.absoluteExpiresAt)}</dd>
              </div>
            </dl>
            <p className="text-sm text-fg-muted">{t("next")}</p>
            <PasswordChangeSection onSignedOut={() => router.replace(LOGIN_PATH)} />
            {state.session.role === "ADMIN" ? <RecoveryRenewal onSignedOut={() => router.replace(LOGIN_PATH)} /> : null}
          </>
        ) : null}

        <Button type="button" onClick={signOut} disabled={signingOut || state.kind === "loading"}>
          {signingOut ? t("signingOut") : t("signOut")}
        </Button>
      </div>
    </AuthShell>
  );
}
